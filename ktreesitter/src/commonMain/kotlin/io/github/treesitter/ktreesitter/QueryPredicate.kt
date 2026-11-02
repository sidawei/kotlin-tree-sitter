package io.github.treesitter.ktreesitter

/**
 * A query [predicate](https://tree-sitter.github.io/tree-sitter/using-parsers#predicates)
 * that associates conditions or arbitrary metadata with a pattern.
 *
 * The following predicates are supported by default:
 *
 * - `#eq?`, `#not-eq?`, `#any-eq?`, `#any-not-eq?`
 * - `#match?`, `#not-match?`, `#any-match?`, `#any-not-match?`
 * - `#any-of?`, `#not-any-of?`
 *
 * @property name The name of the predicate.
 * @property args The arguments given to the predicate.
 */
sealed class QueryPredicate(val name: String) {
    abstract val args: List<QueryPredicateArg>

    /**
     * Evaluate this predicate against [match].
     *
     * [text] returns the source text of a node, or `null` if it is unavailable,
     * in which case predicates that depend on it are ignored.
     */
    internal abstract operator fun invoke(match: QueryMatch, text: (Node) -> CharSequence?): Boolean

    final override fun toString() = "QueryPredicate(name=$name, args=$args)"

    internal class EqCapture(
        name: String,
        private val capture: String,
        private val value: String,
        private val isPositive: Boolean,
        private val isAny: Boolean
    ) : QueryPredicate(name) {
        override val args = listOf(
            QueryPredicateArg.Capture(capture),
            QueryPredicateArg.Capture(value)
        )

        override fun invoke(match: QueryMatch, text: (Node) -> CharSequence?): Boolean {
            val nodes1 = match[capture]
            val nodes2 = match[value]
            val test = if (!isAny) nodes1::all else nodes1::any
            return test { n1 ->
                val text1 = text(n1)
                text1 == null || nodes2.any { n2 ->
                    val text2 = text(n2)
                    text2 == null || (text1.contentEquals(text2) == isPositive)
                }
            }
        }
    }

    internal class EqString(
        name: String,
        private val capture: String,
        private val value: String,
        private val isPositive: Boolean,
        private val isAny: Boolean
    ) : QueryPredicate(name) {
        override val args = listOf(
            QueryPredicateArg.Capture(capture),
            QueryPredicateArg.Literal(value)
        )

        override fun invoke(match: QueryMatch, text: (Node) -> CharSequence?): Boolean {
            val nodes = match[capture]
            if (nodes.isEmpty()) return !isPositive
            val test = if (!isAny) nodes::all else nodes::any
            return test {
                val nodeText = text(it)
                nodeText == null || (value.contentEquals(nodeText) == isPositive)
            }
        }
    }

    internal class Match(
        name: String,
        private val capture: String,
        private val pattern: Regex,
        private val isPositive: Boolean,
        private val isAny: Boolean
    ) : QueryPredicate(name) {
        override val args = listOf(
            QueryPredicateArg.Capture(capture),
            QueryPredicateArg.Literal(pattern.pattern)
        )

        override fun invoke(match: QueryMatch, text: (Node) -> CharSequence?): Boolean {
            val nodes = match[capture]
            if (nodes.isEmpty()) return !isPositive
            val test = if (!isAny) nodes::all else nodes::any
            return test {
                val nodeText = text(it)
                nodeText == null || (pattern.containsMatchIn(nodeText) == isPositive)
            }
        }
    }

    internal class AnyOf(
        name: String,
        private val capture: String,
        private val value: List<String>,
        private val isPositive: Boolean
    ) : QueryPredicate(name) {
        override val args = List(value.size + 1) {
            if (it == 0) QueryPredicateArg.Capture(capture)
            else QueryPredicateArg.Literal(value[it - 1])
        }

        override fun invoke(match: QueryMatch, text: (Node) -> CharSequence?): Boolean =
            match[capture].none {
                val nodeText = text(it)
                nodeText != null &&
                    (value.any { literal -> literal.contentEquals(nodeText) } != isPositive)
            }
    }

    internal class Generic(
        name: String,
        override val args: List<QueryPredicateArg>
    ) : QueryPredicate(name) {
        override fun invoke(match: QueryMatch, text: (Node) -> CharSequence?) = true
    }
}
