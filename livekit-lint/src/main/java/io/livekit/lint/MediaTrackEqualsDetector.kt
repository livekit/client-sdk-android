@file:Suppress("UnstableApiUsage") // We know that Lint API's aren't final.
package io.livekit.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiClassType
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UastBinaryOperator

/**
 * Detects MediaStreamTrack.equals() usage. This is generally a mistake and should not be used.
 */
class MediaTrackEqualsDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes() =
        listOf(UBinaryExpression::class.java, UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        return object : UElementHandler() {

            override fun visitBinaryExpression(node: UBinaryExpression) {
                checkExpression(context, node)
            }

            override fun visitCallExpression(node: UCallExpression) {
                checkCall(context, node)
            }
        }
    }

    private fun checkCall(context: JavaContext, node: UCallExpression) {
        if (node.methodName == "equals") {
            val left = node.receiverType ?: return
            val right = node.valueArguments.takeIf { it.isNotEmpty() }
                ?.get(0)
                ?.getExpressionType()
                ?: return
            if (left is PsiClassType && right is PsiClassType
                && (left.canonicalText == MEDIA_STREAM_TRACK || right.canonicalText == MEDIA_STREAM_TRACK)
            ) {
                val message = DEFAULT_MSG
                val location = context.getLocation(node)
                context.report(ISSUE, node, location, message)
            }
        }
    }

    private fun checkExpression(context: JavaContext, node: UBinaryExpression) {
        if (node.operator == UastBinaryOperator.IDENTITY_EQUALS ||
            node.operator == UastBinaryOperator.EQUALS
        ) {
            val left = node.leftOperand.getExpressionType() ?: return
            val right = node.rightOperand.getExpressionType() ?: return
            if (left is PsiClassType && right is PsiClassType
                && (left.canonicalText == MEDIA_STREAM_TRACK || right.canonicalText == MEDIA_STREAM_TRACK)
            ) {
                val message = DEFAULT_MSG
                val location = node.operatorIdentifier?.let {
                    context.getLocation(it)
                } ?: context.getLocation(node)
                context.report(ISSUE, node, location, message)
            }
        }
    }

    companion object {
        private const val MEDIA_STREAM_TRACK = "org.webrtc.MediaStreamTrack"

        private const val DEFAULT_MSG =
            "Suspicious equality check: MediaStreamTracks should not be checked for equality. Check id() instead."

        private val IMPLEMENTATION =
            Implementation(MediaTrackEqualsDetector::class.java, Scope.JAVA_FILE_SCOPE)

        @JvmField
        val ISSUE = Issue.create(
            id = "MediaTrackEqualsDetector",
            briefDescription = "Suspicious MediaStreamTrack Equality",
            explanation = """
                MediaStreamTrack does not implement `equals`, and therefore cannot be relied upon.
                Additionally, many MediaStreamTrack objects may exist for the same underlying stream,
                and therefore the identity operator `===` is unreliable. 
                """,
            category = Category.CORRECTNESS,
            priority = 4,
            androidSpecific = true,
            moreInfo = "https://github.com/livekit/client-sdk-android/commit/01152f2ac01dae59759383d587cdc21035718b8e",
            severity = Severity.ERROR,
            implementation = IMPLEMENTATION
        )
    }
}