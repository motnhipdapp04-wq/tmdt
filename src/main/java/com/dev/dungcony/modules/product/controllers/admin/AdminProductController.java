package com.dev.dungcony.modules.product.controllers.admin;

import com.dev.dungcony.commons.dtos.ApiRes;
import com.dev.dungcony.modules.product.dtos.req.*;

import com.dev.dungcony.modules.product.services.interfaces.product.ProductAddService;
import com.dev.dungcony.modules.product.services.interfaces.product.ProductDeleteService;
import com.dev.dungcony.modules.product.services.interfaces.product.ProductUpdateService;
import com.dev.dungcony.modules.upload.dtos.res.ImageUploadRes;
import com.dev.dungcony.modules.upload.services.interfaces.ImageUploadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/v1/api/admin/product")
@Tag(name = "Products")
public class AdminProductController {

    private final ProductAddService productAddService;
    private final ProductUpdateService productUpdateService;
    private final ProductDeleteService productDeleteService;
    private final ImageUploadService imageUploadService;

    @PostMapping("/add-new")
    public ResponseEntity<ApiRes<?>> addNew(
            @RequestBody ProductAddReq req) {
        return ResponseEntity.ok()
                .body(ApiRes.success("Add new product successfully",
                        productAddService.addNew(req)));
    }

    @PutMapping("/update")
    public ResponseEntity<ApiRes<?>> update(
            @RequestParam("product_code") String productCode,
            @RequestBody ProductUpdateReq req) {
        return ResponseEntity.ok()
                .body(ApiRes.success("Update product successfully", productUpdateService.update(productCode, req)));
    }

    @PutMapping(value = "/{productCode}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiRes<?>> updateImage(
            @PathVariable String productCode,
            @RequestPart("file") MultipartFile file) {
        ImageUploadRes uploadedImage = imageUploadService.uploadProductImage(productCode, file);
        return ResponseEntity.ok()
                .body(ApiRes.success(
                        "Update product image successfully",
                        productUpdateService.updateImage(productCode, uploadedImage.imageUrl())));
    }

    @DeleteMapping("/delete/{code}")
    public ResponseEntity<Void> delete(
            @PathVariable String code) {
        productDeleteService.delete(code);
        return ResponseEntity
                .ok()
                .build();
    }

}
