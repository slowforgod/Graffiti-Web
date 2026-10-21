package kr.ac.khu.graffiti.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import kr.ac.khu.graffiti.user.UserAccountService;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Security filters are covered by SecurityIT; this test is about the error body only.
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ThrowingController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    // Needed by the @CurrentUser argument resolver that @WebMvcTest picks up.
    @MockitoBean
    private UserAccountService accounts;

    @Test
    void apiExceptionKeepsCodeStatusAndDetails() throws Exception {
        mvc.perform(get("/test/cooldown"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("COOLDOWN"))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.COOLDOWN.defaultMessage()))
                .andExpect(jsonPath("$.error.details.retryAfterSec").value(720));
    }

    @Test
    void apiExceptionCanOverrideMessage() throws Exception {
        mvc.perform(get("/test/page-ended"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAGE_ENDED"))
                .andExpect(jsonPath("$.error.message").value("다른 문구"));
    }

    @Test
    void unhandledExceptionBecomesInternalWithoutLeakingMessage() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(content().string(not(containsString("secret detail"))));
    }

    @Test
    void malformedJsonIsValidationFailed() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void beanValidationFailureNamesTheField() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.field").value("name"))
                .andExpect(jsonPath("$.error.details.reason").value("NotBlank"));
    }

    @Test
    void oversizedUploadIsPayloadTooLarge() throws Exception {
        mvc.perform(get("/test/too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void unknownPathIsNotFoundInApiFormat() throws Exception {
        mvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @RestController
    static class ThrowingController {

        record Body(@NotBlank String name) {
        }

        @GetMapping("/test/cooldown")
        void cooldown() {
            throw new ApiException(ErrorCode.COOLDOWN, Map.of("retryAfterSec", 720));
        }

        @GetMapping("/test/page-ended")
        void pageEnded() {
            throw new ApiException(ErrorCode.PAGE_ENDED, "다른 문구", null);
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret detail");
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/test/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(1);
        }
    }
}
