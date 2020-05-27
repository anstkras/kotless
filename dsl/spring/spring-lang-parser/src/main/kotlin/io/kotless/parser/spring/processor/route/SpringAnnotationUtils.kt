package io.kotless.parser.spring.processor.route

import io.kotless.HttpMethod
import io.kotless.URIPath
import io.kotless.parser.utils.psi.annotation.*
import io.kotless.parser.utils.psi.parents
import io.kotless.parser.utils.psi.withExceptionHeader
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.resolve.BindingContext
import org.springframework.web.bind.annotation.*

object SpringAnnotationUtils {
    private val methodAnnotations = mapOf(
        GetMapping::class to HttpMethod.GET,
        PostMapping::class to HttpMethod.POST,
        PutMapping::class to HttpMethod.PUT,
        PatchMapping::class to HttpMethod.PATCH,
        DeleteMapping::class to HttpMethod.DELETE
    )
    private val anyMethodAnnotation = setOf(RequestMapping::class)

    fun isHTTPHandler(binding: BindingContext, func: KtNamedFunction): Boolean {
        return func.isAnnotatedWith(binding, anyMethodAnnotation) || func.isAnnotatedWith(binding, methodAnnotations.keys)
    }

    fun getMethods(binding: BindingContext, func: KtNamedFunction): Set<HttpMethod> {
        return executeOnAnnotation(binding, func, onMethodAnnotation = { annotation ->
            val fq = annotation.fqName(binding)
            setOf(methodAnnotations.entries.find { it.key.qualifiedName == fq }!!.value)
        }, onAnyMethodAnnotation = { annotation ->
            val methods = annotation.getArrayEnumValue(binding, RequestMapping::method) ?: arrayOf(RequestMethod.GET)
            methods.map { HttpMethod.valueOf(it.name) }.toSet()
        })
    }

    fun getRoutePath(binding: BindingContext, func: KtNamedFunction): URIPath {
        return executeOnAnnotation(binding, func, onMethodAnnotation = { annotation ->
            val existingPath = getRoutePath(func, binding)
            val currentPath = annotation.getURIPaths(binding, "value")
            require(currentPath != null) {
                func.withExceptionHeader("`value` parameter is required for @GetMapping/@PostMapping/... style annotations")
            }
            require(currentPath.size == 1) {
                func.withExceptionHeader("`value` parameter of @GetMapping/@PostMapping/... annotations should have only one path")
            }

            URIPath(existingPath, currentPath.single())
        }, onAnyMethodAnnotation = { annotation ->
            val existingPath = getRoutePath(func, binding)
            val currentPath = annotation.getURIPaths(binding, "value")
            require(currentPath != null) {
                func.withExceptionHeader("`value` parameter is required for @RequestMapping annotation")
            }
            require(currentPath.size == 1) {
                func.withExceptionHeader("`value` parameter of @RequestMapping annotation should have only one path")
            }

            URIPath(existingPath, currentPath.single())
        })
    }

    private fun getRoutePath(element: KtElement, binding: BindingContext): URIPath {
        val routeCalls = element.parents<KtClassOrObject>().filter { it.isAnnotatedWith<RequestMapping>(binding) }.toList().reversed()
        val path = routeCalls.mapNotNull {
            it.getAnnotation<RequestMapping>(binding).getURIPaths(binding, RequestMapping::value)?.singleOrNull()
        }
        return URIPath(path.asSequence())
    }


    private fun <T> executeOnAnnotation(binding: BindingContext, func: KtNamedFunction,
                                        onMethodAnnotation: (KtAnnotationEntry) -> T,
                                        onAnyMethodAnnotation: (KtAnnotationEntry) -> T): T {
        val myMethodAnnotations = func.getAnnotations(binding, methodAnnotations.keys)
        val myAnyMethodAnnotations = func.getAnnotations(binding, anyMethodAnnotation)

        require(myMethodAnnotations.isEmpty() || myAnyMethodAnnotations.isEmpty()) {
            func.withExceptionHeader("Method should be annotated either with @GetMapping/@PostMapping/... or @RequestMapping. Not both at once!")
        }

        return if (myMethodAnnotations.isNotEmpty()) {
            require(myMethodAnnotations.size == 1) {
                func.withExceptionHeader("Method should be annotated only with one annotation of @GetMapping/@PostMapping/... type")
            }
            val annotation = myMethodAnnotations.single()
            onMethodAnnotation(annotation)
        } else {
            require(myAnyMethodAnnotations.size == 1) {
                func.withExceptionHeader("Method should be annotated only with one annotation of @RequestMapping type")
            }
            val annotation = myAnyMethodAnnotations.single()
            onAnyMethodAnnotation(annotation)
        }
    }
}
