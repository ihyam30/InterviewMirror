package local.interviewmirror.backend.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructuredDocumentParserTest {
    private final StructuredDocumentParser parser = new StructuredDocumentParser();

    @Test
    void extractsQuestionsAndAnswersFromMineruHtmlTable() {
        String markdown = """
                ## Question Bank Q11
                <table><tbody><tr><td colspan="2">How would you design a reliable document parsing task?</td><td colspan="2">How do you prevent duplicate worker claims? How should an expired task lease be recovered?</td></tr><tr><td colspan="4"></td></tr><tr><td>#</td><td colspan="2">Question</td><td>Answer</td></tr><tr><td>1</td><td colspan="2">How would you design a reliable document parsing task?</td><td>Synthetic answer one</td></tr><tr><td>2</td><td colspan="2">How do you prevent duplicate worker claims?</td><td>Synthetic answer two</td></tr><tr><td>3</td><td colspan="2">How should an expired task lease be recovered?</td><td>Synthetic answer three</td></tr></tbody></table>
                """;

        var questions = parser.parse(DocumentType.QUESTION_BANK, markdown).path("questions");

        assertEquals(3, questions.size());
        assertEquals("How would you design a reliable document parsing task?",
                questions.get(0).path("stem").asString());
        assertEquals("Synthetic answer one", questions.get(0).path("answer").asString());
        assertEquals("How should an expired task lease be recovered?",
                questions.get(2).path("stem").asString());
    }
}
