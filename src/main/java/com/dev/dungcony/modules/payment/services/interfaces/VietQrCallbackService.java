package com.dev.dungcony.modules.payment.services.interfaces;

import com.dev.dungcony.modules.payment.dtos.req.VietQrTransactionSyncReq;
import com.dev.dungcony.modules.payment.dtos.res.VietQrTokenRes;
import com.dev.dungcony.modules.payment.dtos.res.VietQrTransactionSyncRes;

import java.util.Optional;

public interface VietQrCallbackService {
    // Tao Bearer token cho VietQR sau khi VietQR gui Basic Auth hop le
    Optional<VietQrTokenRes> createToken(String authorizationHeader);

    // Kiem tra Bearer token trong request callback transaction-sync cua VietQR
    boolean validateBearerToken(String authorizationHeader);

    // Xu ly giao dich ngan hang VietQR gui ve va cap nhat trang thai don hang neu hop le
    VietQrTransactionSyncRes syncTransaction(VietQrTransactionSyncReq req);
}
