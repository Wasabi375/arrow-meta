package arrow.meta.ide.phases.resolve

import arrow.meta.quotes.ktFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.junit.Test

class QuoteSystemCacheTest : LightPlatformCodeInsightFixture4TestCase() {
  /** This test updates a PsiFile in place and validated the cache afterwards. */
  @Test
  fun testIncrementalCacheUpdate() {
    val file = myFixture.addFileToProject("testArrow/source.kt", "package testArrow")

    // this is the initial rebuild and the initial cache population
    val cache = QuoteSystemCache.getInstance(project)
    //cache.forceRebuild() no need

    val code = """
      package testArrow
      import arrow.higherkind

      @higherkind
      class IdOriginal<out A>(val value: A)
    """.trimIndent()

    updateAndAssertCache(cache, project, myFixture, file, code, 0, 5)
  }

  /** This test creates two PsiFiles, updates one after the other in place and validates the cache state. */
  @Test
  fun testIncrementalCacheUpdateMultipleFiles() {
    val codeFirst = """
      package testArrow
      import arrow.higherkind
      @higherkind
      class IdOriginalFirst<out A>(val value: A)
    """.trimIndent()

    val codeSecond = """
      package testArrow
      import arrow.higherkind
      @higherkind
      class IdOriginalSecond<out A>(val value: A)
    """.trimIndent()

    val codeThird = """
      package testArrowOther
      import arrow.higherkind
      @higherkind
      class IdOriginalThird<out A>(val value: A)
    """.trimIndent()

    val fileFirst = myFixture.addFileToProject("testArrow/first.kt", codeFirst)
    val fileSecond = myFixture.addFileToProject("testArrow/second.kt", codeSecond)
    // fileThird is in a different package
    val fileThird = myFixture.addFileToProject("testArrowOther/third.kt", codeThird)

    // this is the initial rebuild and the initial cache population
    val cache = QuoteSystemCache.getInstance(project)
    // cache.forceRebuild()
    cache.refreshCache(project.collectAllKtFiles(), backgroundTask = false)

    updateAndAssertCache(cache, project, myFixture, fileFirst, codeFirst.replace("IdOriginalFirst", "IdRenamedFirst"), 10, 10) { retained ->
      assertTrue("nothing from the original file must be retained", retained.none { it.ktFile()?.name?.contains("first") == true })
    }
    updateAndAssertCache(cache, project, myFixture, fileSecond, codeSecond.replace("IdOriginalSecond", "IdRenamedSecond"), 10, 10) { retained ->
      assertTrue("nothing from the original file must be retained", retained.none { it.ktFile()?.name?.contains("second") == true })
    }
    updateAndAssertCache(cache, project, myFixture, fileThird, codeThird.replace("IdOriginalThird", "IdRenamedThird"), 5, 5) { retained ->
      assertTrue("previously cached elements of the updated PsiFile must have been dropped from the cache: $retained",
        retained.isEmpty())
    }

    // remove all meta-related source from the first file
    // and make sure that all the descriptors are removed from the cache
    updateAndAssertCache(cache, project, myFixture, fileFirst, "package testArrow", 10, 5) { retained ->
      assertTrue("nothing from the original file must be retained", retained.none { it.ktFile()?.name?.contains("first") == true })
    }
  }
}