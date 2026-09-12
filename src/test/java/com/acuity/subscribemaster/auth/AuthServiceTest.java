package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
        * Pure unit test -- no Spring context, no infrastructure. Mocks every
 * AuthService dependency to isolate its actual logic.
 */
@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock private CustomerRepository customerRepo;
    @Mock private PasswordEncoder pwdEncoder;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private AuthService authService;

    @Test
    void successfulRegistration_savesHashedPasswordAndRecordsAuditEvent() {
        // Arrange:
        //   - customerRepo.existsByEmail(...) returns false
        //   - pwdEncoder.encode(...) returns some known hashed value, distinct
        //     from the raw plaintext input, so you can prove it's the HASH
        //     that gets saved, not the raw password
        //   - customerRepo.saveAndFlush(...) returns a Customer with a real
        //     id/createdAt populated (simulating what the DB would return)

        // Act:
        //   - call authService.register(email, password, ipAddress)

        // Assert:
        //   - the Customer object passed to saveAndFlush had the HASHED
        //     password, not the raw one (capture the argument, don't just
        //     check the response)
        //   - auditLogService.recordEvent(...) was called exactly once,
        //     with the correct actorType/actorId/action/resource/resourceId
        //   - the returned RegistrationResponse reflects the saved
        //     customer's real id/email/createdAt
    }

    @Test
    void duplicateEmail_throwsAndNeverTouchesRepositoryOrAuditLog() {
        // Arrange:
        //   - customerRepo.existsByEmail(...) returns true

        // Act + Assert:
        //   - calling register(...) throws UserAlreadyExistsException
        //     (assertThatThrownBy is the natural fit here)

        // Assert (the important part):
        //   - verify(customerRepo, never()).saveAndFlush(any())
        //   - verify(auditLogService, never()).recordEvent(any(), any(), any(), any(), any(), any())
        //   This proves the rejection path short-circuits cleanly, rather
        //   than partially executing before the exception is thrown.
    }
}
