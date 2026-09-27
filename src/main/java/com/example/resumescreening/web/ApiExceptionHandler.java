package com.example.resumescreening.web;

import com.example.resumescreening.job.JobNotFoundException;
import com.example.resumescreening.screening.InvalidResumeException;
import com.example.resumescreening.typesafe.MissingApiKeyException;
import com.example.resumescreening.typesafe.TypeSafeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler
	ProblemDetail jobNotFound(JobNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail invalidResume(InvalidResumeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail missingApiKey(MissingApiKeyException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail typeSafeFailure(TypeSafeException ex) {
		log.error("TypeSafe call failed", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		if (ex.status() != null) {
			problem.setProperty("upstreamStatus", ex.status());
		}
		return problem;
	}

}
