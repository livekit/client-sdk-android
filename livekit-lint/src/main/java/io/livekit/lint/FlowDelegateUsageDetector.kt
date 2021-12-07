@file:Suppress("UnstableApiUsage") // We know that Lint API's aren't final.

/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.*
import org.jetbrains.uast.kotlin.KotlinUQualifiedReferenceExpression

/** Checks related to DiffUtil computation. */
class FlowDelegateUsageDetector : Detector(), SourceCodeScanner {

    override fun visitReference(
        context: JavaContext,
        visitor: JavaElementVisitor?,
        reference: PsiJavaCodeReferenceElement,
        referenced: PsiElement
    ) {
        super.visitReference(context, visitor, reference, referenced)
    }

    override fun visitReference(context: JavaContext, reference: UReferenceExpression, referenced: PsiElement) {

        // Check if we're actually trying to access the flow delegate
        val referencedMethod = referenced as? PsiMethod ?: return
        if (referenced.name != "getFlow" || referencedMethod.containingClass?.qualifiedName != "io.livekit.android.util.FlowObservableKt") {
            return
        }

        // This should get the property we're trying to receive the flow from.
        val receiver = ((reference.uastParent as? KotlinUQualifiedReferenceExpression)
            ?.receiver as? UCallableReferenceExpression)
            ?: return

        // This should get the original class associated with the property.
        val className = receiver.qualifierType?.canonicalText
        val psiClass = if (className != null) context.evaluator.findClass(className) else null
        val psiField = psiClass?.findFieldByName("${receiver.callableName}\$delegate", true)
        val isAnnotated = psiField?.hasAnnotation("io.livekit.android.util.FlowObservable") ?: false

        if (!isAnnotated) {
            val message = DEFAULT_MSG
            val location = context.getLocation(reference)
            context.report(ISSUE, reference, location, message)
        }
    }

    override fun getApplicableReferenceNames(): List<String>? =
        listOf("flow")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        super.visitMethodCall(context, node, method)
    }

    override fun getApplicableUastTypes() =
        listOf(
            UBinaryExpression::class.java,
            UCallExpression::class.java,
            UAnnotation::class.java,
            UArrayAccessExpression::class.java,
            UBinaryExpressionWithType::class.java,
            UBlockExpression::class.java,
            UBreakExpression::class.java,
            UCallableReferenceExpression::class.java,
            UCatchClause::class.java,
            UClass::class.java,
            UClassLiteralExpression::class.java,
            UContinueExpression::class.java,
            UDeclaration::class.java,
            UDoWhileExpression::class.java,
            UElement::class.java,
            UEnumConstant::class.java,
            UExpression::class.java,
            UExpressionList::class.java,
            UField::class.java,
            UFile::class.java,
            UForEachExpression::class.java,
            UForExpression::class.java,
            UIfExpression::class.java,
            UImportStatement::class.java,
            UClassInitializer::class.java,
            ULabeledExpression::class.java,
            ULambdaExpression::class.java,
            ULiteralExpression::class.java,
            ULocalVariable::class.java,
            UMethod::class.java,
            UObjectLiteralExpression::class.java,
            UParameter::class.java,
            UParenthesizedExpression::class.java,
            UPolyadicExpression::class.java,
            UPostfixExpression::class.java,
            UPrefixExpression::class.java,
            UQualifiedReferenceExpression::class.java,
            UReturnExpression::class.java,
            USimpleNameReferenceExpression::class.java,
            USuperExpression::class.java,
            USwitchClauseExpression::class.java,
            USwitchExpression::class.java,
            UThisExpression::class.java,
            UThrowExpression::class.java,
            UTryExpression::class.java,
            UTypeReferenceExpression::class.java,
            UUnaryExpression::class.java,
            UVariable::class.java,
            UWhileExpression::class.java,
            UYieldExpression::class.java,

            )

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        return object : UElementHandler() {
            val context = context
            override fun visitBinaryExpression(node: UBinaryExpression) {
                println(0)
            }

            override fun visitCallExpression(node: UCallExpression) {
                node.classReference
                node.methodName
                node.methodIdentifier
                node.receiverType
                node.receiver
                node.kind
                node.valueArguments
                node.valueArgumentCount
                node.typeArguments
                node.typeArgumentCount
                node.returnType
                println(1)
            }

            override fun visitAnnotation(node: UAnnotation) {
                println(0)
            }

            override fun visitArrayAccessExpression(node: UArrayAccessExpression) {
                println(0)
            }

            override fun visitBinaryExpressionWithType(node: UBinaryExpressionWithType) {
                println(0)
            }

            override fun visitBlockExpression(node: UBlockExpression) {
                println(0)
            }

            override fun visitBreakExpression(node: UBreakExpression) {
                println(0)
            }

            override fun visitCallableReferenceExpression(node: UCallableReferenceExpression) {
                println(0)
            }

            override fun visitCatchClause(node: UCatchClause) {
                println(0)
            }

            override fun visitClass(node: UClass) {
                println(0)
            }

            override fun visitClassLiteralExpression(node: UClassLiteralExpression) {
                println(0)
            }

            override fun visitContinueExpression(node: UContinueExpression) {
                println(0)
            }

            override fun visitDeclaration(node: UDeclaration) {
                println(0)
            }

            override fun visitDeclarationsExpression(node: UDeclarationsExpression) {
                println(0)
            }

            override fun visitDoWhileExpression(node: UDoWhileExpression) {
                println(0)
            }

            override fun visitElement(node: UElement) {
                println(0)
            }

            override fun visitEnumConstant(node: UEnumConstant) {
                println(0)
            }

            override fun visitExpression(node: UExpression) {
                println(0)
            }

            override fun visitExpressionList(node: UExpressionList) {
                println(0)
            }

            override fun visitField(node: UField) {
                println(0)
            }

            override fun visitFile(node: UFile) {
                println(0)
            }

            override fun visitForEachExpression(node: UForEachExpression) {
                println(0)
            }

            override fun visitForExpression(node: UForExpression) {
                println(0)
            }

            override fun visitIfExpression(node: UIfExpression) {
                println(0)
            }

            override fun visitImportStatement(node: UImportStatement) {
                println(0)
            }

            override fun visitInitializer(node: UClassInitializer) {
                println(0)
            }

            override fun visitLabeledExpression(node: ULabeledExpression) {
                println(0)
            }

            override fun visitLambdaExpression(node: ULambdaExpression) {
                println(0)
            }

            override fun visitLiteralExpression(node: ULiteralExpression) {
                println(0)
            }

            override fun visitLocalVariable(node: ULocalVariable) {
                println(0)
            }

            override fun visitMethod(node: UMethod) {
                println(0)
            }


            override fun visitObjectLiteralExpression(node: UObjectLiteralExpression) {
                println(0)
            }

            override fun visitParameter(node: UParameter) {
                println(0)
            }

            override fun visitParenthesizedExpression(node: UParenthesizedExpression) {
                println(0)
            }

            override fun visitPolyadicExpression(node: UPolyadicExpression) {
                println(0)
            }

            override fun visitPostfixExpression(node: UPostfixExpression) {
                println(0)
            }

            override fun visitPrefixExpression(node: UPrefixExpression) {
                println(0)
            }

            override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression) {
                println(0)
            }

            override fun visitReturnExpression(node: UReturnExpression) {
                println(0)
            }

            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
                println(0)

                //(((node as KotlinUSimpleReferenceExpression).uastParent as KotlinUQualifiedReferenceExpression).receiver as KotlinUCallableReferenceExpression).qualifierType?.canonicalText
            }

            override fun visitSuperExpression(node: USuperExpression) {
                println(0)
            }

            override fun visitSwitchClauseExpression(node: USwitchClauseExpression) {
                println(0)
            }

            override fun visitSwitchExpression(node: USwitchExpression) {
                println(0)
            }

            override fun visitThisExpression(node: UThisExpression) {
                println(0)
            }

            override fun visitThrowExpression(node: UThrowExpression) {
                println(0)
            }

            override fun visitTryExpression(node: UTryExpression) {
                println(0)
            }

            override fun visitTypeReferenceExpression(node: UTypeReferenceExpression) {
                println(0)
            }

            override fun visitUnaryExpression(node: UUnaryExpression) {
                println(0)
            }

            override fun visitVariable(node: UVariable) {
                println(0)
            }

            override fun visitWhileExpression(node: UWhileExpression) {
                println(0)
            }

            override fun visitYieldExpression(node: UYieldExpression) {
                println(0)
            }
        }
    }

    companion object {
        private const val MEDIA_STREAM_TRACK = "org.webrtc.MediaStreamTrack"

        private const val DEFAULT_MSG =
            "Incorrect flow property usage: Only properties marked with the @FlowObservable annotation can be observed using `io.livekit.android.util.flow`."

        private val IMPLEMENTATION =
            Implementation(FlowDelegateUsageDetector::class.java, Scope.JAVA_FILE_SCOPE)

        @JvmField
        val ISSUE = Issue.create(
            id = "FlowDelegateUsageDetector",
            briefDescription = "flow on a non-@FlowObservable property",
            explanation = """
                Only properties marked with the @FlowObservable annotation can be observed using
                `io.livekit.android.util.flow`.
                """,
            category = Category.CORRECTNESS,
            priority = 4,
            androidSpecific = true,
            moreInfo = "https://issuetracker.google.com/116789824",
            severity = Severity.ERROR,
            implementation = IMPLEMENTATION
        )
    }
}