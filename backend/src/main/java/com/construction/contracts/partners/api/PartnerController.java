package com.construction.contracts.partners.api;

import com.construction.contracts.identity.api.RequestIdentity;
import com.construction.contracts.partners.application.PartnerService;
import com.construction.contracts.partners.application.PartnerService.Fields;
import com.construction.contracts.partners.application.PartnerStore.Search;
import com.construction.contracts.partners.domain.Partner;
import com.construction.contracts.partners.domain.Partner.Status;
import com.construction.contracts.web.VersionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PartnerController {
    private static final String REFERENCE = "[A-Za-z0-9_-]{1,40}";
    record Create(@NotNull @Pattern(regexp = REFERENCE) String referenceCode,
                  @NotBlank @Size(max = 160) String legalName,
                  @Size(max = 80) String registrationIdentifier, @Email @Size(max = 254) String contactEmail) {}
    record Update(@Pattern(regexp = REFERENCE) String referenceCode, @Size(max = 160) String legalName,
                  @Size(max = 80) String registrationIdentifier, @Email @Size(max = 254) String contactEmail,
                  Status status) {}
    record Detail(String id, String referenceCode, String legalName, String registrationIdentifier,
                  String contactEmail, Status status, String version) {}
    record Summary(String id, String referenceCode, String legalName) {}
    record Page<T>(List<T> items, int page, int size, long total) {}

    private final PartnerService service;

    PartnerController(PartnerService service) {
        this.service = service;
    }

    @GetMapping("/partners")
    Page<Detail> list(@RequestParam(defaultValue = "") @Size(max = 160) String q,
                      @RequestParam(required = false) Status status,
                      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size, HttpServletRequest request) {
        var result = service.list(org(request), actor(request), new Search(q, status, page * size, size));
        return new Page<>(result.items().stream().map(PartnerController::detail).toList(), page, size, result.total());
    }

    @GetMapping("/projects/{projectId}/partner-options")
    Page<Summary> lookup(@PathVariable long projectId, @RequestParam(defaultValue = "") @Size(max = 160) String q,
                         @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                         @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size, HttpServletRequest request) {
        var result = service.lookup(org(request), actor(request), projectId, q, page * size, size);
        return new Page<>(result.items().stream().map(item -> new Summary(Long.toString(item.id()),
            item.referenceCode(), item.legalName())).toList(), page, size, result.total());
    }

    @PostMapping("/partners")
    ResponseEntity<Detail> create(@Valid @RequestBody Create input, HttpServletRequest request) {
        var partner = service.create(org(request), actor(request), new Fields(input.referenceCode(), input.legalName(),
            input.registrationIdentifier(), input.contactEmail()));
        return ResponseEntity.created(URI.create("/api/v1/partners/" + partner.id()))
            .eTag(VersionHeaders.etag(partner.version())).body(detail(partner));
    }

    @GetMapping("/partners/{partnerId}")
    ResponseEntity<Detail> get(@PathVariable long partnerId, HttpServletRequest request) {
        return response(service.get(org(request), actor(request), partnerId));
    }

    @PatchMapping("/partners/{partnerId}")
    ResponseEntity<Detail> update(@PathVariable long partnerId,
                                  @RequestHeader(value = "If-Match", required = false) String match,
                                  @Valid @RequestBody Update input, HttpServletRequest request) {
        return response(service.update(org(request), actor(request), partnerId, VersionHeaders.requireVersion(match),
            new Fields(input.referenceCode(), input.legalName(), input.registrationIdentifier(), input.contactEmail()),
            input.status()));
    }

    private static long org(HttpServletRequest request) { return RequestIdentity.selectedOrganization(request); }
    private static long actor(HttpServletRequest request) { return RequestIdentity.userId(request.getUserPrincipal()); }

    private static ResponseEntity<Detail> response(Partner partner) {
        return ResponseEntity.ok().eTag(VersionHeaders.etag(partner.version())).body(detail(partner));
    }

    private static Detail detail(Partner partner) {
        return new Detail(Long.toString(partner.id()), partner.referenceCode(), partner.legalName(),
            partner.registrationIdentifier(), partner.contactEmail(), partner.status(), Long.toString(partner.version()));
    }
}
