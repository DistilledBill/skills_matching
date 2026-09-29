package com.example.resumescreening.web;

import java.util.List;

import com.example.resumescreening.screening.ResumeFile;
import com.example.resumescreening.screening.ResumeFolder;
import com.example.resumescreening.screening.ResumeLoader;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The resume library under the resumes root. */
@RestController
@RequestMapping("/api")
public class ResumeController {

	private final ResumeLoader loader;

	public ResumeController(ResumeLoader loader) {
		this.loader = loader;
	}

	@GetMapping("/resume-folders")
	public List<ResumeFolder> folders() {
		return loader.listFolders();
	}

	@GetMapping("/resume-folders/{folder}")
	public List<ResumeFile> folder(@PathVariable String folder) {
		return loader.listFolder(folder);
	}

}
