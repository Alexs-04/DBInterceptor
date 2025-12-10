package atlix.dbiceptor.logic.config.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.servlet.ModelAndView

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleException(e: Exception): ModelAndView {
        val mav = ModelAndView("error")
        mav.addObject("message", e.message ?: "An unexpected error occurred.")
        return mav
    }
}