package com.example.resumescreening.screening;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.resumescreening.config.ScreeningProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeLoaderTest {

	private final ResumeLoader loader = new ResumeLoader(new ScreeningProperties(Path.of("jobs"), Path.of("resumes")));

	@Test
	void extractsTextFromPdfUpload() throws IOException {
		MockMultipartFile pdf = new MockMultipartFile("files", "Jane Resume.PDF", "application/pdf",
				pdf("Senior Python engineer, 8 years"));

		List<ResumeDocument> resumes = loader.fromUploads(List.of(pdf));

		assertThat(resumes).singleElement().satisfies(r -> {
			assertThat(r.name()).isEqualTo("Jane Resume");
			assertThat(r.text()).contains("Senior Python engineer, 8 years");
		});
	}

	@Test
	void stripsClientSuppliedDirectoriesFromUploadNames() {
		MockMultipartFile file = new MockMultipartFile("files", "../../etc/candidate.txt", "text/plain",
				"hello".getBytes());

		assertThat(loader.fromUploads(List.of(file))).extracting(ResumeDocument::name).containsExactly("candidate");
	}

	@Test
	void corruptPdfIsInvalidResume() {
		MockMultipartFile bad = new MockMultipartFile("files", "bad.pdf", "application/pdf", "not a pdf".getBytes());

		assertThatThrownBy(() -> loader.fromUploads(List.of(bad))).isInstanceOf(InvalidResumeException.class)
			.hasMessageContaining("bad.pdf");
	}

	@Test
	void readsSupportedFilesFromJobFolderInOrder() {
		assertThat(loader.fromFolder("senior_backend_engineer_candidates")).extracting(ResumeDocument::name)
			.containsExactly("candidate_a", "candidate_b", "candidate_c", "candidate_d", "candidate_e", "candidate_f",
					"candidate_g", "candidate_h", "candidate_i", "candidate_j");
	}

	@Test
	void doesNotDescendIntoSubfolders() {
		assertThatThrownBy(() -> loader.fromFolder("")).isInstanceOf(InvalidResumeException.class)
			.hasMessageContaining("resumes found in ''");
	}

	@Test
	void readsOneResumeFromAFolderIncludingPdfText(@TempDir Path root) throws IOException {
		ResumeLoader temp = libraryWith(root);
		byte[] pdfBytes = pdf("Senior Python engineer, 8 years");
		Files.write(root.resolve("jobs_candidates/candidate_p.pdf"), pdfBytes);

		ResumeContent txt = temp.readOne("jobs_candidates", "candidate_t.txt");
		assertThat(txt.fileName()).isEqualTo("candidate_t.txt");
		assertThat(txt.name()).isEqualTo("candidate_t");
		assertThat(txt.format()).isEqualTo("txt");
		assertThat(txt.text()).contains("t@example.com");
		assertThat(txt.redacted().text()).contains("[email]").doesNotContain("t@example.com");

		ResumeContent pdf = temp.readOne("jobs_candidates", "candidate_p.pdf");
		assertThat(pdf.format()).isEqualTo("pdf");
		assertThat(pdf.text()).contains("Senior Python engineer, 8 years");
		assertThat(temp.rawFile("jobs_candidates", "candidate_p.pdf")).hasBinaryContent(pdfBytes);
	}

	@Test
	void readOneRejectsNamesThatLeaveTheFolder(@TempDir Path root) throws IOException {
		ResumeLoader temp = libraryWith(root);
		Files.writeString(root.resolve("secret.txt"), "outside");
		for (String bad : new String[] { "../secret.txt", "sub/candidate_t.txt", "..\\secret.txt", "" }) {
			assertThatThrownBy(() -> temp.readOne("jobs_candidates", bad)).as(bad)
				.isInstanceOf(InvalidResumeException.class);
		}
		assertThatThrownBy(() -> temp.readOne("jobs_candidates", "notes.docx")).isInstanceOf(InvalidResumeException.class)
			.hasMessageContaining("Unsupported");
		assertThatThrownBy(() -> temp.readOne("..", "secret.txt")).isInstanceOf(InvalidResumeException.class);
		assertThatThrownBy(() -> temp.readOne("jobs_candidates", "nobody.txt"))
			.isInstanceOf(ResumeNotFoundException.class);
	}

	@Test
	void readOneRejectsSymlinksPointingOutOfTheFolder(@TempDir Path root) throws IOException {
		ResumeLoader temp = libraryWith(root);
		Path outside = Files.writeString(root.resolveSibling(root.getFileName() + "-outside.txt"), "outside");
		try {
			Files.createSymbolicLink(root.resolve("jobs_candidates/link.txt"), outside);
			assertThatThrownBy(() -> temp.readOne("jobs_candidates", "link.txt"))
				.isInstanceOf(InvalidResumeException.class);
		}
		finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void uploadContentsKeepFileNameAndFormat() throws IOException {
		List<ResumeContent> contents = loader.uploadContents(List.of(
				new MockMultipartFile("files", "cv.pdf", "application/pdf", pdf("Staff engineer")),
				new MockMultipartFile("files", "notes.md", "text/markdown", "# Candidate M".getBytes())));
		assertThat(contents).extracting(ResumeContent::fileName).containsExactly("cv.pdf", "notes.md");
		assertThat(contents).extracting(ResumeContent::format).containsExactly("pdf", "md");
		assertThat(contents.get(0).text()).contains("Staff engineer");
	}

	private static ResumeLoader libraryWith(Path root) throws IOException {
		Path folder = Files.createDirectories(root.resolve("jobs_candidates"));
		Files.writeString(folder.resolve("candidate_t.txt"), "Candidate T\nt@example.com | (555) 201-0000\n");
		return new ResumeLoader(new ScreeningProperties(Path.of("jobs"), root));
	}

	private static byte[] pdf(String text) throws IOException {
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(72, 700);
				content.showText(text);
				content.endText();
			}
			doc.save(out);
			return out.toByteArray();
		}
	}

}
