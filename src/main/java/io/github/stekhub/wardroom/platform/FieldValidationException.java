package io.github.stekhub.wardroom.platform;

/**
 * A request field broke a rule. The API answers with {@code 400} and names the field and the rule.
 *
 * <p>The message is shown to the owner. It describes the rule and never repeats the value that was sent.
 */
public class FieldValidationException extends RuntimeException {

    private final String field;

    /**
     * @param field   the name of the field in the request body
     * @param message the rule that failed, as a sentence
     */
    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
