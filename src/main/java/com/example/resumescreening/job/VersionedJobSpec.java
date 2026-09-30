package com.example.resumescreening.job;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A job spec with the version of its file: a SHA-256 of the file's bytes when it was loaded. Saving sends the
 * version back so an edit made on disk since then is not overwritten.
 */
public record VersionedJobSpec(@JsonUnwrapped JobSpec job, String version) {
}
