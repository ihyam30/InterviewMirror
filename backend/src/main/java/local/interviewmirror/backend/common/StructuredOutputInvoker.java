package local.interviewmirror.backend.common;

import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/** Converts one model response and rejects malformed or invalid structured output. */
@Component
public class StructuredOutputInvoker {
    public <T, R> R invoke(ChatClient client, String system, String user, BeanOutputConverter<T> converter,
            Function<T, R> validate) {
        String response = client.prompt().system(system + "\n" + converter.getFormat())
                .user(user).call().content();
        final T output;
        try {
            output = converter.convert(response);
        } catch (RuntimeException invalidFormat) {
            throw new IllegalStateException("Structured output format invalid", invalidFormat);
        }
        if (output == null) throw new IllegalStateException("Structured output format invalid: empty output");
        try {
            return validate.apply(output);
        } catch (RuntimeException invalidValue) {
            throw new IllegalStateException("Structured output validation failed", invalidValue);
        }
    }
}
