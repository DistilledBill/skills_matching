package com.example.resumescreening.job;

import java.util.List;

/** A job spec that fails validation; {@link #errors()} lists every problem found. */
public class InvalidJobSpecException extends RuntimeException {

	private final List<String> errors;

	public InvalidJobSpecException(String id, List<String> errors) {
		super("Invalid job spec '" + id + "': " + String.join("; ", errors));
		this.errors = List.copyOf(errors);
	}

	public List<String> errors() {
		return errors;
	}

}
