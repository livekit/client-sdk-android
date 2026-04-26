/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Detects kotlinx.coroutines `withTimeout` calls.
 */
class NoWithTimeout(config: Config) : Rule(config) {
    override val issue = Issue(
        "NoWithTimeout",
        Severity.Defect,
        "withTimeout cancels the whole coroutine on timeout; use withDeadline or withTimeoutOrNull instead.",
        Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!isKotlinxWithTimeout(expression)) return
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Do not use kotlinx.coroutines.withTimeout.",
            ),
        )
    }

    private fun isKotlinxWithTimeout(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression ?: return false
        return when (callee) {
            is KtNameReferenceExpression -> {
                if (callee.getReferencedName() != "withTimeout") return false
                expression.containingKtFile.importsKotlinxWithTimeout()
            }
            is KtDotQualifiedExpression -> callee.isKotlinxCoroutinesWithTimeout()
            else -> false
        }
    }

    private fun KtFile.importsKotlinxWithTimeout(): Boolean {
        if (importDirectives.any { it.importsKotlinxWithTimeout() }) {
            return true
        }
        // Fallback for environments where import PSI is incomplete.
        return text.lineSequence()
            .map { it.substringBefore("//").trim() }
            .any { line ->
                line == "import kotlinx.coroutines.withTimeout" ||
                    line.startsWith("import kotlinx.coroutines.withTimeout as ") ||
                    line == "import kotlinx.coroutines.*"
            }
    }

    private fun KtImportDirective.importsKotlinxWithTimeout(): Boolean {
        if (isAllUnder && importedFqName?.asString() == "kotlinx.coroutines") {
            return true
        }
        if (!isAllUnder && importedFqName?.asString() == "kotlinx.coroutines.withTimeout") {
            return true
        }
        val path = importPath?.pathStr
        return path == "kotlinx.coroutines.withTimeout" || path == "kotlinx.coroutines.*"
    }

    private fun KtDotQualifiedExpression.isKotlinxCoroutinesWithTimeout(): Boolean {
        val selector = selectorExpression as? KtNameReferenceExpression ?: return false
        if (selector.getReferencedName() != "withTimeout") return false
        if (text.removeSurrounding("`") == "kotlinx.coroutines.withTimeout") return true
        // Nested: kotlinx.coroutines.withTimeout(…) — not a single DotQualified string in all PSI versions.
        val recv = receiverExpression
        if (recv is KtDotQualifiedExpression) {
            val coroutines = recv.selectorExpression as? KtNameReferenceExpression
            val kotlinx = recv.receiverExpression as? KtNameReferenceExpression
            return kotlinx?.getReferencedName() == "kotlinx" &&
                coroutines?.getReferencedName() == "coroutines"
        }
        return false
    }
}
