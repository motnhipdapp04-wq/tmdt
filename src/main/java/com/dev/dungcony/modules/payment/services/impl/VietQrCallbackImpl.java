package com.dev.dungcony.modules.payment.services.impl;

import com.dev.dungcony.modules.order.dtos.OrderDto;
import com.dev.dungcony.modules.order.enums.OrderStatus;
import com.dev.dungcony.modules.order.enums.PaymentType;
import com.dev.dungcony.modules.order.services.interfaces.OrderGetService;
import com.dev.dungcony.modules.order.services.interfaces.OrderUpdateService;
import com.dev.dungcony.modules.payment.config.VietQrProperties;
import com.dev.dungcony.modules.payment.dtos.req.VietQrTransactionSyncReq;
import com.dev.dungcony.modules.payment.dtos.res.VietQrTokenRes;
import com.dev.dungcony.modules.payment.dtos.res.VietQrTransactionSyncRes;
import com.dev.dungcony.modules.payment.exceptions.PaymentException;
import com.dev.dungcony.modules.payment.services.interfaces.VietQrCallbackService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class VietQrCallbackImpl implements VietQrCallbackService {

    // Prefix header ma VietQR dung khi goi API lay token va dong bo giao dich
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";
    private static final String TOKEN_SUBJECT = "vietqr-callback";

    // Chap nhan ma don co dang ORD-YYYYMMDD-XXXX hoac ORDYYYYMMDDXXXX trong noi dung chuyen khoan
    private static final Pattern ORDER_CODE_PATTERN =
            Pattern.compile("ORD-?\\d{8}-?[A-Z0-9]{4}", Pattern.CASE_INSENSITIVE);

    // Cau hinh VietQR va service order dung de doi chieu giao dich voi don hang trong DB
    private final VietQrProperties properties;
    private final OrderGetService orderGetService;
    private final OrderUpdateService orderUpdateService;

    @Override
    public Optional<VietQrTokenRes> createToken(String authorizationHeader) {
        // VietQR goi endpoint token_generate bang Basic Auth: username:password
        String[] credentials = parseBasicCredentials(authorizationHeader);
        if (credentials == null || credentials.length != 2) {
            return Optional.empty();
        }

        // Sai username/password thi khong cap token
        boolean usernameMatches = secureEquals(credentials[0], properties.callbackUsername());
        boolean passwordMatches = secureEquals(credentials[1], properties.callbackPassword());
        if (!usernameMatches || !passwordMatches) {
            return Optional.empty();
        }

        // Tao JWT ngan han de VietQR dung lam Bearer token khi goi transaction-sync
        Date now = new Date();
        Date expiration = Date.from(Instant.ofEpochMilli(now.getTime())
                .plusSeconds(properties.callbackTokenTtlSeconds()));

        String token = Jwts.builder()
                .setSubject(TOKEN_SUBJECT)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(signingKey(), SignatureAlgorithm.HS512)
                .compact();

        return Optional.of(new VietQrTokenRes(token, "Bearer", properties.callbackTokenTtlSeconds()));
    }

    @Override
    public boolean validateBearerToken(String authorizationHeader) {
        // Callback transaction-sync phai gui Authorization: Bearer <token>
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            return false;
        }

        try {
            // Token hop le khi verify duoc chu ky va subject dung voi token cua callback VietQR
            String subject = Jwts.parserBuilder()
                    .setSigningKey(signingKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
            return TOKEN_SUBJECT.equals(subject);
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Token VietQR gửi callback không hợp lệ: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public VietQrTransactionSyncRes syncTransaction(VietQrTransactionSyncReq req) {
        //kiem tra payload co du thong tin co ban va dung tai khoan nhan tien
        validateCallbackPayload(req);

        //lay ma don hang tu orderId hoac noi dung chuyen khoan
        String orderCode = resolveOrderCode(req);

        //lay don hang trong DB de doi chieu loai thanh toan, so tien, trang thai
        OrderDto order = orderGetService.getDtoByCode(orderCode);

        //chi chap nhan don ONLINE, so tien khop, va don chua bi dong
        validateOrder(order, req);

        //neu don dang UNPAID thi cap nhat sang PAID
        markOrderPaidIfNeeded(order);

        // Tra ma giao dich de VietQR biet BE da xu ly thanh cong callback nay
        String refTransactionId = firstNotBlank(req.transactionid(), req.referencenumber(), orderCode);
        log.info("Đã đồng bộ giao dịch VietQR: đơn hàng={}, giao dịch={}, số tiền={}",
                orderCode, refTransactionId, req.amount());

        return VietQrTransactionSyncRes.success(refTransactionId);
    }

    private void validateCallbackPayload(VietQrTransactionSyncReq req) {
        // Payload rong thi khong co du lieu giao dich de xu ly
        if (req == null) {
            throw new PaymentException("VIETQR_EMPTY_BODY", "Payload VietQR trống");
        }
        // Chua cau hinh account/token VietQR thi khong xu ly callback
        if (!properties.configured()) {
            throw new PaymentException("VIETQR_NOT_CONFIGURED", "VietQR chưa được cấu hình");
        }
        // Chi ghi nhan giao dich ghi co tien vao tai khoan
        if (!"C".equalsIgnoreCase(trimToEmpty(req.transType()))) {
            throw new PaymentException("VIETQR_NOT_CREDIT", "Giao dịch VietQR không phải ghi có");
        }
        // So tien phai lon hon 0
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new PaymentException("VIETQR_INVALID_AMOUNT", "Số tiền VietQR không hợp lệ");
        }
        // Bank account VietQR gui ve phai trung voi account dang cau hinh nhan tien
        if (!sameValue(properties.accountNo(), req.bankaccount())) {
            throw new PaymentException("VIETQR_WRONG_BANK_ACCOUNT", "Tài khoản nhận không khớp cấu hình VietQR");
        }
    }

    private void validateOrder(OrderDto order, VietQrTransactionSyncReq req) {
        // Chi don ONLINE moi duoc thanh toan bang QR ngan hang
        if (order.paymentType() != PaymentType.ONLINE) {
            throw new PaymentException("VIETQR_NOT_ONLINE_ORDER", "Đơn hàng không phải thanh toán online");
        }

        // So tien callback phai khop voi finalPrice cua don hang sau khi lam tron ve VND
        BigDecimal expected = toVndAmount(order.finalPrice());
        BigDecimal actual = toVndAmount(req.amount());
        if (expected.compareTo(actual) != 0) {
            throw new PaymentException(
                    "VIETQR_AMOUNT_MISMATCH",
                    "Số tiền VietQR không khớp đơn hàng");
        }

        // Don da huy/hoan thi khong ghi nhan thanh toan nua
        if (order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.RETURNED) {
            throw new PaymentException("VIETQR_ORDER_CLOSED", "Đơn hàng đã đóng, không thể ghi nhận thanh toán");
        }
    }

    private void markOrderPaidIfNeeded(OrderDto order) {
        // Diem cap nhat trang thai: OrderUpdateService se set status tu UNPAID sang PAID trong transaction
        if (order.status() == OrderStatus.UNPAID) {
            orderUpdateService.userPaidOrder(order.uid(), order.orderCode());
        }
    }

    private String resolveOrderCode(VietQrTransactionSyncReq req) {
        // Uu tien orderId neu VietQR gui rieng ma don
        String orderCode = extractOrderCode(req.orderId());
        if (orderCode == null) {
            // Neu khong co orderId thi quet ma don trong noi dung chuyen khoan
            orderCode = extractOrderCode(req.content());
        }

        if (orderCode == null) {
            throw new PaymentException("VIETQR_ORDER_CODE_NOT_FOUND", "Không tìm thấy mã đơn trong giao dịch VietQR");
        }

        return orderCode;
    }

    private String extractOrderCode(String value) {
        // Khong co chuoi dau vao thi khong the tim ma don
        if (value == null || value.isBlank()) {
            return null;
        }

        // Tim ma don trong chuoi bat ky, sau do chuan hoa ve dang ORD-YYYYMMDD-XXXX
        Matcher matcher = ORDER_CODE_PATTERN.matcher(value.toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }

        String cleaned = matcher.group().replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() != 15) {
            return null;
        }

        return "ORD-" + cleaned.substring(3, 11) + "-" + cleaned.substring(11);
    }

    private String[] parseBasicCredentials(String authorizationHeader) {
        // Header lay token phai co dang Authorization: Basic base64(username:password)
        if (authorizationHeader == null || !authorizationHeader.startsWith(BASIC_PREFIX)) {
            return null;
        }

        try {
            String raw = authorizationHeader.substring(BASIC_PREFIX.length()).trim();
            String decoded = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            return decoded.split(":", 2);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Key signingKey() {
        // Khoa ky JWT duoc lay tu cau hinh callback token secret
        return Keys.hmacShaKeyFor(properties.callbackTokenSecret().getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal toVndAmount(BigDecimal amount) {
        // VietQR doi chieu so tien theo VND, khong lay phan thap phan
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    private boolean secureEquals(String left, String right) {
        // So sanh credential bang constant-time compare de tranh ro ri timing
        byte[] leftBytes = trimToEmpty(left).getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = trimToEmpty(right).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(leftBytes, rightBytes);
    }

    private boolean sameValue(String left, String right) {
        // So sanh text sau khi trim, dung cho cac gia tri cau hinh/callback
        return trimToEmpty(left).equals(trimToEmpty(right));
    }

    private String firstNotBlank(String... values) {
        // Lay gia tri dau tien co noi dung de lam ma tham chieu giao dich tra ve VietQR
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String trimToEmpty(String value) {
        // Chuan hoa null thanh chuoi rong de cac ham so sanh khong bi NullPointerException
        return value == null ? "" : value.trim();
    }
}
