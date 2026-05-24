package korebit.dbiceptor.exception

import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.ModelAndView

/**
 * Centralized MVC error handler that maps common exceptions to error views.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    /**
     * Handles validation errors raised by Spring MVC.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidationException(e: MethodArgumentNotValidException): ModelAndView {
        val message = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { "Invalid request." }
        return buildErrorView(message)
    }

    /**
     * Handles invalid input errors coming from business logic.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ModelAndView {
        return buildErrorView(e.message ?: "Invalid request.")
    }

    /**
     * Handles missing resources.
     */
    @ExceptionHandler(NoSuchElementException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFoundException(e: NoSuchElementException): ModelAndView {
        return buildErrorView(e.message ?: "Resource not found.")
    }

    /**
     * Handles database access problems.
     */
    @ExceptionHandler(DataAccessException::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleDataAccessException(e: DataAccessException): ModelAndView {
        return buildErrorView(e.message ?: "Database error occurred.")
    }

    /**
     * Generic fallback for unexpected errors.
     */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleException(e: Exception): ModelAndView {
        return buildErrorView(e.message ?: "An unexpected error occurred.")
    }

    private fun buildErrorView(message: String): ModelAndView {
        val mav = ModelAndView("error")
        mav.addObject("message", message)
        return mav
    }
}