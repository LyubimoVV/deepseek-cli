package com.example.deepseek.invariant;

public class InvariantViolationException extends Exception {
    
    private final ValidationResult.UserRequestViolation violation;
    
    public InvariantViolationException(ValidationResult.UserRequestViolation violation) {
        super(violation.message());
        this.violation = violation;
    }
    
    public ValidationResult.UserRequestViolation getViolation() {
        return violation;
    }
}
