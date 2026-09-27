package com.example.resumescreening.job;

public class JobNotFoundException extends RuntimeException {

	public JobNotFoundException(String id) {
		super("No job spec with id '" + id + "'");
	}

}
