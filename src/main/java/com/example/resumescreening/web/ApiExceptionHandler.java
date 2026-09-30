package com.example.resumescreening.web;

import com.example.resumescreening.assist.AssistException;
import com.example.resumescreening.assist.AssistUnavailableException;
import com.example.resumescreening.job.InvalidJobSpecException;
import com.example.resumescreening.job.JobNotFoundException;
import com.example.resumescreening.job.JobSpecConflictException;
import com.example.resumescreening.screening.InvalidResumeException;
import com.example.resumescreening.screening.ResumeNotFoundException;
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

	/** 400 with every problem in {@code errors}, so the editor can show them all at once. */
	@ExceptionHandler
	ProblemDetail invalidJobSpec(InvalidJobSpecException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setProperty("errors", ex.errors());
		return problem;
	}

	@ExceptionHandler
	ProblemDetail jobSpecConflict(JobSpecConflictException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail assistUnavailable(AssistUnavailableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail assistFailed(AssistException ex) {
		log.warn("Spec suggestion failed: {}", ex.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
	}

	/** A request the handler can't act on, such as an out-of-range level count. */
	@ExceptionHandler
	ProblemDetail badArgument(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail resumeNotFound(ResumeNotFoundException ex) {
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
