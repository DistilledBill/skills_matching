package com.example.resumescreening.screening;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.example.resumescreening.config.ScreeningProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

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
