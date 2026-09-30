package com.example.resumescreening.web;

import java.net.URI;
import java.util.List;

import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.job.JobSpecRepository;
import com.example.resumescreening.job.VersionedJobSpec;
import com.example.resumescreening.screening.ResumeLoader;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job specs: list, create, save, and reload from disk. Saving writes {@code jobs/<id>.yaml}, so this belongs
 * behind authentication before the app is exposed beyond localhost.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

	private final JobSpecRepository jobs;

	private final ResumeLoader resumes;

	public JobController(JobSpecRepository jobs, ResumeLoader resumes) {
		this.jobs = jobs;
		this.resumes = resumes;
	}

	@GetMapping
	public List<VersionedJobSpec> list() {
		return jobs.allVersioned();
	}

	/** Creates {@code jobs/<id>.yaml} and the job's {@code resumes/<id>_candidates/} folder. */
	@PostMapping
	public ResponseEntity<VersionedJobSpec> create(@RequestBody JobSpec spec) {
		VersionedJobSpec created = jobs.create(spec);
		resumes.ensureFolder(created.job().id() + "_candidates");
		return ResponseEntity.created(URI.create("/api/jobs/" + created.job().id())).body(created);
	}

	/** Saves a spec. {@code version} must match the file on disk, or the save is refused with 409. */
	@PutMapping("/{id}")
	public VersionedJobSpec update(@PathVariable String id, @RequestBody UpdateJob body) {
		return jobs.update(id, body.spec(), body.version());
	}

	@PostMapping("/{id}/reload")
	public VersionedJobSpec reload(@PathVariable String id) {
		return jobs.reload(id);
	}

	@PostMapping("/reload")
	public JobSpecRepository.ReloadResult reloadAll() {
		return jobs.reloadAll();
	}

	public record UpdateJob(String version, JobSpec spec) {
	}

}
