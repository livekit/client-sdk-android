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

import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.kotlin.KotlinUQualifiedReferenceExpression
import org.jetbrains.uast.tryResolve

/** Checks related to DiffUtil computation. */
class FlowDelegateUsageDetector : Detector(), SourceCodeScanner {

    override fun visitReference(context: JavaContext, reference: UReferenceExpression, referenced: PsiElement) {
        // Check if we're actually trying to access the flow delegate
        val referencedMethod = referenced as? PsiMethod ?: return
        if (referenced.name != GET_FLOW || referencedMethod.containingClass?.qualifiedName != FLOW_DELEGATE) {
            return
        }

        // This should get the getter we're trying to receive the flow from.
        val parent = reference.uastParent as? KotlinUQualifiedReferenceExpression
        val rec = parent?.receiver
        val resolve = rec?.tryResolve()
        println("parent: $parent, rec: $rec, resolve: $resolve")
        val receiver = ((reference.uastParent as? KotlinUQualifiedReferenceExpression)
            ?.receiver as? UCallableReferenceExpression)
            ?.resolve()

        val isAnnotated = when (receiver) {
            is PsiMethod -> {
                println("${receiver.name},  ${receiver.annotations.fold("") { total, next -> "$total, ${next.text}" }}")
                receiver.hasAnnotation(FLOW_OBSERVABLE_ANNOTATION)
            }
            is PsiField -> {
                val receiverClass = (receiver.type as? PsiClassType)?.resolve()
                println("$receiverClass,  ${receiverClass?.annotations?.fold("") { total, next -> "$total, ${next.text}" }}")
                receiverClass?.hasAnnotation(FLOW_OBSERVABLE_ANNOTATION) ?: false
            }
            else -> {
                false
            }
        }

        if (!isAnnotated) {
            val message = DEFAULT_MSG
            val location = context.getLocation(reference)
            context.report(ISSUE, reference, location, message)
        }
    }

    override fun getApplicableReferenceNames(): List<String>? =
        listOf("flow")

    companion object {

        // The name of the method for the flow accessor
        private const val GET_FLOW = "getFlow"

        // The containing file and implicitly generated class
        private const val FLOW_DELEGATE = "io.livekit.android.util.FlowDelegateKt"

        private const val FLOW_OBSERVABLE_ANNOTATION = "io.livekit.android.util.FlowObservable"

        private const val DEFAULT_MSG =
            "Incorrect flow property usage: Only properties marked with the @FlowObservable " +
                "annotation can be observed using `io.livekit.android.util.flow`. " +
                "Improper usage will result in a NullPointerException."

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
