package arrow.meta.quotes.scope.plugins

import arrow.meta.Meta
import arrow.meta.Plugin
import arrow.meta.invoke
import arrow.meta.phases.CompilerContext
import arrow.meta.quotes.Transform
import arrow.meta.quotes.lambdaExpression

open class LambdaExpressionsPlugin : Meta {
  override fun intercept(ctx: CompilerContext): List<Plugin> = listOf(
    lambdaExpressionPlugin
  )
}

val Meta.lambdaExpressionPlugin
  get() =
    "Lambda Expression Scope Plugin" {
      meta(
        lambdaExpression({ true }) { expression ->
          Transform.replace(
            replacing = expression,
            newDeclaration = identity()
          )
        }
      )
    }