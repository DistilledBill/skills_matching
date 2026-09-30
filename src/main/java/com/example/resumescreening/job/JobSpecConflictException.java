package com.example.resumescreening.job;

/** A save that would overwrite a job spec file that changed, or a job id that is already taken. */
public class JobSpecConflictException extends RuntimeException {

	public JobSpecConflictException(String message) {
		super(message);
	}

}
