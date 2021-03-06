package arrow.meta.ide.phases.resolve

import arrow.meta.phases.CompilerContext
import arrow.meta.phases.analysis.ElementScope
import arrow.meta.quotes.analysisIdeExtensions
import arrow.meta.quotes.processKtFile
import arrow.meta.quotes.updateFiles
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.FileIndexFacade
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.debugger.readAction
import org.jetbrains.kotlin.idea.search.projectScope
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.lazy.LazyEntity
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * QuoteSystemCache is a project component which manages the transformations of KtFiles by the quote system.
 *
 * When initialized, it transforms all .kt files of the project in a background thread.
 */
class QuoteSystemCache(private val project: Project) : ProjectComponent, Disposable {
  companion object {
    fun getInstance(project: Project): QuoteSystemCache = project.getComponent(QuoteSystemCache::class.java)

    /**
     * messages is used to report messages generated by quote system via IntelliJ's log.
     */
    private val messages: MessageCollector = object : MessageCollector {
      override fun clear() {}

      override fun hasErrors(): Boolean = false

      override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
        when {
          severity.isError -> LOG.error(message)
          severity.isWarning -> LOG.warn(message)
          else -> LOG.debug(message)
        }
      }
    }

    private val KEY_DOC_UPDATE = Key.create<ProgressIndicator>("arrow.quoteDocUpdate")
  }

  private val metaCache: MetaTransformationCache = DefaultMetaTransformationCache()

  // pool where the quote system transformations are executed.
  // this is a single thread pool to avoid concurrent updates to the cache.
  // we keep a pool per project, so that we're able to shut it down when the project is closed
  private val cacheUpdaterPool: ExecutorService = AppExecutorUtil.createBoundedApplicationPoolExecutor("arrow worker", 1)
  // pool where non-blocking read actions for document updates are executed
  private val docUpdaterPool: ExecutorService = AppExecutorUtil.createBoundedApplicationPoolExecutor("arrow doc worker", 1)

  // fixme find a good value for timeout (milliseconds)
  private val editorUpdateQueue = MergingUpdateQueue("arrow doc worker", 500, true, null, this, null, Alarm.ThreadToUse.POOLED_THREAD)

  override fun initComponent() {
    // register an async file listener.
    // We need to update the transformations of .kt files as soon as they were modified.
    VirtualFileManager.getInstance().addAsyncFileListener(object : AsyncFileListener {
      // We only care about changes to Kotlin files.
      override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        // fixme properly handle remove events
        // fixme properly handle copy event: file is the source file, transform new file, too

        val relevantFiles: List<VirtualFile> = events.mapNotNull {
          val file = it.file
          if (it is VFileContentChangeEvent && it is VFileMoveEvent && it is VFileCopyEvent && file != null && file.isRelevantFile()) {
            file
          } else {
            null
          }
        }

        if (relevantFiles.isEmpty()) {
          return null
        }

        return object : AsyncFileListener.ChangeApplier {
          override fun afterVfsChange() {
            LOG.info("afterVfsChange")
            // fixme data may have changed between prepareChange and afterVfsChange, take care of this
            refreshCache(relevantFiles.toKtFiles(project), resetCache = false, indicator = DumbProgressIndicator.INSTANCE)
          }
        }
      }
    }, this)

    EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : BulkAwareDocumentListener.Simple {
      override fun afterDocumentChange(document: Document) {
        editorUpdateQueue.queue(Update.create(document) {
          //  cancel ongoing updates of the same document
          KEY_DOC_UPDATE.get(document)?.let {
            KEY_DOC_UPDATE.set(document, null)
            it.cancel()
          }

          val indicator: ProgressIndicator = EmptyProgressIndicator(ModalityState.NON_MODAL)
          KEY_DOC_UPDATE.set(document, indicator)

          if (ApplicationManager.getApplication().isUnitTestMode) {
            readAction {
              ProgressManager.getInstance().runProcess({
                onDocUpdate(document, indicator)
              }, indicator)
            }
          } else {
            ReadAction.nonBlocking {
              ProgressManager.getInstance().runProcess({
                onDocUpdate(document, indicator)
              }, indicator)
            }.cancelWith(indicator)
              .expireWhen(indicator::isCanceled)
              .expireWith(this@QuoteSystemCache)
              .submit(docUpdaterPool)
          }
        })
      }
    }, project)
  }

  private fun onDocUpdate(doc: Document, progressIndicator: ProgressIndicator) {
    ApplicationManager.getApplication().assertReadAccessAllowed()

    val vFile = FileDocumentManager.getInstance().getFile(doc)
    if (vFile == null
      || vFile is LightVirtualFile
      || !vFile.isRelevantFile()
      || !FileIndexFacade.getInstance(project).isInSourceContent(vFile)) {
      return
    }

    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc)
    if (psiFile is KtFile && psiFile.isPhysical && !psiFile.isCompiled) {
      LOG.info("transforming ${psiFile.name} after change in editor")
      // fixme avoid this, this slows down the editor.
      //  it would be better to take the text and send the text content to the quote system
      // fixme this breaks in a live ide with "com.intellij.util.IncorrectOperationException: Must not modify PSI inside save listener"
      //   but doesn't fail in tests
      if (ApplicationManager.getApplication().isWriteAccessAllowed) {
        PsiDocumentManager.getInstance(project).commitDocument(doc)
      }

      refreshCache(listOf(psiFile), resetCache = false, indicator = progressIndicator)
    }
  }

  override fun projectOpened() {
    // add a startup activity to populate the cache with a transformation of all project files
    // fixme sometimes initially opened files still show errors.
    //  This seems to be a timing issue between cache update and initial update.
    StartupManager.getInstance(project).runWhenProjectIsInitialized {
      runBackgroundableTask("Arrow Meta", project, cancellable = false) {
        LOG.info("Initializing quote system cache...")
        val files = readAction {
          project.collectAllKtFiles()
        }
        refreshCache(files, resetCache = true, indicator = DumbProgressIndicator.INSTANCE)
      }
    }
  }

  fun packageList(): List<FqName> = metaCache.packageList()

  fun resolved(name: FqName): List<DeclarationDescriptor>? = metaCache.resolved(name)

  /**
   * refreshCache updates the given source files with new transformations.
   * The transformations are executed in the background to avoid blocking the IDE.
   *
   * @param resetCache Defines if all previous transformations should be removed or not. Pass false for incremental updates.
   */
  private fun refreshCache(updatedFiles: List<KtFile>, resetCache: Boolean = true, indicator: ProgressIndicator) {
    LOG.assertTrue(indicator.isRunning)
    LOG.info("refreshCache(): updating/adding ${updatedFiles.size} files, currently cached ${metaCache.size} files")

    if (updatedFiles.isEmpty()) {
      return
    }

    // non–blocking read action mode may execute multiple times until the action finished without being cancelled
    // writes cancel non–blocking read actions, e.g. typing in the editor is triggering a write action
    // a matching progress indicator is passed by the caller to decide when the transformation must not be repeated anymore
    // a blocking read action can lead to very bad editor experience, especially we're doing a lot with the PsiFiles
    // in the transformation
    ReadAction.nonBlocking<List<Pair<KtFile, KtFile>>> {
      val start = System.currentTimeMillis()
      try {
        transformFiles(updatedFiles)
      } finally {
        val duration = System.currentTimeMillis() - start
        LOG.warn("kt file transformation: %d files, duration %d ms".format(updatedFiles.size, duration))
      }
    }.cancelWith(indicator)
      .expireWhen({ indicator.isCanceled })
      .submit(docUpdaterPool)
      .then { transformed ->
        // limit to one pool to avoid cache corruption
        ProgressManager.getInstance().runProcess({
          doRefreshCache(updatedFiles, transformed, resetCache, indicator)
        }, indicator)
      }.onError { e ->
        // fixme atm a transformation of a .kt file with syntax errors also returns an empty list of transformations
        //    we probably need to handle this, otherwise files with errors will always have unresolved references
        //    best would be partial transformation results for the valid parts of a file (Quote system changes needed)

        // IllegalStateExceptions are usually caused by syntax errors in the source files, thrown by quote system
        if (LOG.isDebugEnabled) {
          LOG.debug("error transforming files $updatedFiles", e)
        }
      }
  }

  /**
   * Update the cache with the transformed data as soon as index access is available.
   * The execution of the cache update may be delayed.
   * This method takes care that only one cache update may happen at the same time by using a single-bounded executor.
   */
  private fun doRefreshCache(updatedFiles: List<KtFile>, transformed: List<Pair<KtFile, KtFile>>, resetCache: Boolean, indicator: ProgressIndicator) {
    LOG.assertTrue(indicator.isRunning)

    ReadAction.nonBlocking {
      ProgressManager.getInstance().runProcess({
        LOG.assertTrue(indicator.isRunning)
        LOG.info("resolving descriptors of transformed files: ${transformed.size} files")

        if (resetCache) {
          metaCache.clear()
        }

        if (updatedFiles.isNotEmpty()) {
          // clear descriptors of all updatedFiles, which don't have a transformation result
          // e.g. because meta-code isn't used anymore

          // fixme this lookup is slow (exponential?), optimize when necessary
          for (updated in updatedFiles) {
            if (!transformed.any { it.first == updated }) {
              metaCache.removeTransformations(updated)
            }
          }

          // the kotlin facade needs files with source and target elements
          val kotlinCache = KotlinCacheService.getInstance(project)
          val facade = kotlinCache.getResolutionFacade(updatedFiles + transformed.map { it.second })

          // fixme: remove descriptors which belong to the newly transformed files
          transformed.forEach { (sourceFile, transformedFile) ->
            // fixme this triggers a resolve which already queries our synthetic resolve extensions
            val synthDescriptors = transformedFile.declarations.mapNotNull {
              val desc = facade.resolveToDescriptor(it, BodyResolveMode.FULL)
              if (desc.isMetaSynthetic()) desc else null
            }

            if (synthDescriptors.isNotEmpty()) {
              synthDescriptors.forEach { synthDescriptor ->
                try {
                  // fixme this triggers a call to the .resolved()
                  //    via MetaSyntheticPackageFragmentProvider.BuildCachePackageFragmentDescriptor.Scope.getContributedClassifier
                  if (synthDescriptor is LazyEntity) synthDescriptor.forceResolveAllContents()
                } catch (e: IndexNotReadyException) {
                  LOG.warn("Index wasn't ready to resolve: ${synthDescriptor.name}")
                }
              }
            }

            metaCache.updateTransformations(sourceFile, transformedFile, synthDescriptors)
          }

          // refresh the highlighting of editors of modified files, using the new cache
          for ((originalPsiFile, _) in transformed) {
            DaemonCodeAnalyzer.getInstance(project).restart(originalPsiFile)
          }
        }
      }, indicator)
    }.cancelWith(indicator)
      .expireWhen({ indicator.isCanceled })
      .expireWith(this)
      .inSmartMode(project)
      .submit(cacheUpdaterPool)
  }

  /**
   * Applies the Quote system's transformations on the input files and returns a mapping of
   * originalFile->transformedFile if the transformation changed the original file.
   */
  private fun transformFiles(sourceFiles: List<KtFile>): List<Pair<KtFile, KtFile>> {
    ApplicationManager.getApplication().assertReadAccessAllowed()
    LOG.assertTrue(ProgressManager.getInstance().hasProgressIndicator())

    // fixme is scope correct here? Unsure what CompilerContext is expecting here
    // fixme do we need to set more properties of the compiler context?
    val context = CompilerContext(project, messages, ElementScope.default(project))
    context.files = sourceFiles

    val resultFiles = arrayListOf<KtFile>()
    resultFiles.addAll(sourceFiles)

    // fixme: remove debugging code before it's used in production
    val allDuration = AtomicLong(0)
    analysisIdeExtensions.forEach { ext ->
      ProgressManager.checkCanceled()

      val mutations = resultFiles.map {
        ProgressManager.checkCanceled()

        val start = System.currentTimeMillis()
        try {
          // fixme add checkCancelled to processKtFile? The API should be available
          processKtFile(it, ext.type, ext.quoteFactory, ext.match, ext.map)
        } finally {
          val fileDuration = System.currentTimeMillis() - start
          allDuration.addAndGet(fileDuration)
          LOG.warn("transformation: file %s, duration %d".format(it.name, fileDuration))
        }
      }
      LOG.warn("created transformations for all quotes: duration $allDuration ms")

      ProgressManager.checkCanceled()

      val start = System.currentTimeMillis()
      try {
        // this replaces the entries of resultFiles with transformed files, if transformations apply.
        // a file may be transformed multiple times
        // fixme add checkCancelled to updateFiles? The API should be available
        context.updateFiles(resultFiles, mutations, ext.match)
      } finally {
        val updateDuration = System.currentTimeMillis() - start
        LOG.warn("update of ${resultFiles.size} files with ${mutations.size} mutations: duration $updateDuration ms")
        allDuration.addAndGet(updateDuration)
      }
    }

    LOG.warn("transformation and update of all quotes and all files: duration $allDuration")

    // now, restore the association of sourceFile to transformed file
    // don't keep files which were not transformed
    ProgressManager.checkCanceled()
    return sourceFiles.zip(resultFiles).filter { it.first != it.second }
  }

  override fun dispose() {
    try {
      cacheUpdaterPool.shutdownNow()
    } catch (e: Exception) {
      LOG.warn("error shutting down pool", e)
    }

    metaCache.clear()
  }

  @TestOnly
  fun reset() {
    metaCache.clear()
  }

  @TestOnly
  fun forceRebuild() {
    reset()
    refreshCache(project.collectAllKtFiles(), indicator = DumbProgressIndicator.INSTANCE)
    flushForTest()
  }

  @TestOnly
  fun flushForTest() {
    editorUpdateQueue.flush()

    // use sleep, until we find a better way to wait for non blocking read actions
    Thread.sleep(5000)
  }
}

private fun VirtualFile.isRelevantFile(): Boolean {
  return isValid &&
    this.fileType is KotlinFileType &&
    (isInLocalFileSystem || ApplicationManager.getApplication().isUnitTestMode)
}

/**
 * Collects all Kotlin files of the current project which are source files for Meta transformations.
 */
private fun Project.collectAllKtFiles(): List<KtFile> {
  val files = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, projectScope()).filter {
    it.isRelevantFile()
  }
  LOG.info("collectKtFiles(): ${files.size} kotlin files found for project $name")

  return files.toKtFiles(this)
}

/**
 * Maps the list of VirtualFiles to a list of KtFiles.
 */
private fun List<VirtualFile>.toKtFiles(project: Project): List<KtFile> {
  val psiMgr = PsiManager.getInstance(project)
  return mapNotNull {
    when {
      it.isValid && it.isInLocalFileSystem && it.fileType is KotlinFileType ->
        // fixme use ViewProvider's files instead?
        psiMgr.findFile(it) as? KtFile
      else -> null
    }
  }
}

