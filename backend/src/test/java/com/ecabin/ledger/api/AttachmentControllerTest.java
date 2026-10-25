package com.ecabin.ledger.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ecabin.ledger.security.CurrentUserResolver;
import com.ecabin.ledger.security.PermissionService;
import com.ecabin.ledger.service.AttachmentService;
import com.ecabin.ledger.service.DefectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AttachmentControllerTest {
    @Test
    void rejectsMultipleFilePartsBeforeAuthorizationOrStorageCanProcessThem() {
        AttachmentService attachments=mock(AttachmentService.class);
        CurrentUserResolver users=mock(CurrentUserResolver.class);
        PermissionService permissions=mock(PermissionService.class);
        DefectService defects=mock(DefectService.class);
        AttachmentController controller=new AttachmentController(attachments,users,permissions,defects);

        assertThrows(IllegalArgumentException.class,()->controller.upload(null,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"evidence",List.of(
            new MockMultipartFile("file","one.pdf","application/pdf",new byte[]{1}),
            new MockMultipartFile("file","two.pdf","application/pdf",new byte[]{1}))));

        verifyNoInteractions(attachments,users,permissions,defects);
    }
}
