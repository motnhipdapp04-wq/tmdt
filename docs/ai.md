# Phân loại câu hỏi (Intent Classification) cho chatbot TMĐT

## Tổng quan

Thay vì đọc toàn bộ database mỗi lần user hỏi, chatbot cần **xác định ý định (intent)** trước — rồi chỉ query đúng bảng
liên quan. File này hướng dẫn 3 cách triển khai từ đơn giản đến nâng cao.

---

## Các intent cần xử lý

Dựa trên schema hiện tại, có 6 intent chính:

| Intent           | Ví dụ câu hỏi                                      | Bảng cần query                                    |
|------------------|----------------------------------------------------|---------------------------------------------------|
| `product_search` | "Có áo Nike không?", "Cho xem giày size 42"        | `tbl_products`, `tbl_providers`, `tbl_categories` |
| `order_status`   | "Đơn hàng của tôi đâu rồi?", "ORD-123 đang ở đâu?" | `tbl_orders`, `tbl_order_items`                   |
| `cart_info`      | "Giỏ hàng tôi có gì?", "Tổng tiền bao nhiêu?"      | `tbl_cart_items`, `tbl_products`                  |
| `voucher_info`   | "Tôi có voucher nào?", "Mã giảm giá còn không?"    | `tbl_user_vouchers`, `tbl_vouchers`               |
| `product_review` | "Sản phẩm này review thế nào?", "Đánh giá áo thun" | `tbl_comments`, `tbl_products`                    |
| `general`        | "Shop giao hàng bao lâu?", "Chính sách đổi trả?"   | Không query DB — trả lời từ FAQ                   |

---

## Cách 1 — Keyword matching (nhanh, không cần AI)

Phù hợp khi câu hỏi có pattern rõ ràng. Chi phí gần như bằng 0.

```python
import re
from dataclasses import dataclass
from typing import Optional

@dataclass
class IntentResult:
    intent: str
    confidence: float
    extracted: dict  # entity đã trích xuất được

# Từ khóa cho từng intent
INTENT_KEYWORDS = {
    "product_search": [
        "sản phẩm", "áo", "quần", "giày", "dép", "túi", "mũ", "nón",
        "có không", "tìm", "xem", "mua", "giá", "size", "màu", "hàng"
    ],
    "order_status": [
        "đơn hàng", "đơn", "order", "giao hàng", "vận chuyển",
        "shipper", "tracking", "ORD-", "khi nào", "đến chưa", "hủy đơn"
    ],
    "cart_info": [
        "giỏ hàng", "cart", "thanh toán", "checkout",
        "tổng tiền", "xóa", "thêm vào giỏ"
    ],
    "voucher_info": [
        "voucher", "mã giảm giá", "coupon", "khuyến mãi",
        "discount", "giảm", "ưu đãi", "mã"
    ],
    "product_review": [
        "đánh giá", "review", "nhận xét", "bình luận",
        "có tốt không", "chất lượng", "mọi người nói"
    ],
}

def classify_by_keywords(question: str) -> IntentResult:
    question_lower = question.lower()
    scores = {}

    for intent, keywords in INTENT_KEYWORDS.items():
        hit = sum(1 for kw in keywords if kw in question_lower)
        if hit > 0:
            scores[intent] = hit

    if not scores:
        return IntentResult(intent="general", confidence=1.0, extracted={})

    best_intent = max(scores, key=scores.get)
    confidence = min(scores[best_intent] / 3, 1.0)  # normalize về 0-1

    # Trích xuất entity đơn giản
    extracted = extract_entities(question)

    return IntentResult(intent=best_intent, confidence=confidence, extracted=extracted)


def extract_entities(question: str) -> dict:
    entities = {}

    # Tìm mã đơn hàng
    order_code = re.search(r"ORD-[\w-]+", question, re.IGNORECASE)
    if order_code:
        entities["order_code"] = order_code.group()

    # Tìm size
    size_match = re.search(r"\b(S|M|L|XL|XXL|XXXL|\d{2})\b", question, re.IGNORECASE)
    if size_match:
        entities["size"] = size_match.group().upper()

    # Tìm tên thương hiệu
    brands = ["nike", "adidas", "uniqlo", "zara", "h&m", "coolmate", "vans", "converse"]
    for brand in brands:
        if brand in question.lower():
            entities["brand"] = brand.upper()
            break

    return entities


# Ví dụ sử dụng
if __name__ == "__main__":
    questions = [
        "Có áo Nike size M không?",
        "Đơn hàng ORD-26826125-BC2A của tôi đang ở đâu?",
        "Tôi có voucher nào dùng được không?",
        "Áo thun này review thế nào?",
        "Shop giao hàng mấy ngày?",
    ]

    for q in questions:
        result = classify_by_keywords(q)
        print(f"Q: {q}")
        print(f"   → intent={result.intent}, confidence={result.confidence:.1f}, entities={result.extracted}\n")
```

**Output mẫu:**

```
Q: Có áo Nike size M không?
   → intent=product_search, confidence=0.7, entities={'size': 'M', 'brand': 'NIKE'}

Q: Đơn hàng ORD-26826125-BC2A của tôi đang ở đâu?
   → intent=order_status, confidence=1.0, entities={'order_code': 'ORD-26826125-BC2A'}

Q: Tôi có voucher nào dùng được không?
   → intent=voucher_info, confidence=0.7, entities={}
```

---

## Cách 2 — Dùng Claude API để phân loại (chính xác hơn)

Phù hợp khi câu hỏi mơ hồ hoặc nhiều ý. Claude tự hiểu ngữ cảnh và trả về JSON.

```python
import json
import httpx
from dataclasses import dataclass

SYSTEM_PROMPT = """
Bạn là bộ phân loại intent cho chatbot e-commerce. Phân tích câu hỏi và trả về JSON.

Các intent hợp lệ:
- product_search: tìm kiếm, hỏi về sản phẩm, giá, size, màu, thương hiệu
- order_status: hỏi về đơn hàng, tình trạng giao hàng, hủy đơn
- cart_info: hỏi về giỏ hàng, thanh toán
- voucher_info: hỏi về voucher, mã giảm giá, khuyến mãi
- product_review: hỏi đánh giá, nhận xét về sản phẩm
- general: câu hỏi chung về shop, chính sách, không cần query DB

Trả về ĐÚNG định dạng JSON sau, không thêm gì khác:
{
  "intent": "<intent>",
  "confidence": <0.0 đến 1.0>,
  "extracted": {
    "product_name": "<tên sản phẩm nếu có>",
    "brand": "<thương hiệu nếu có>",
    "size": "<size nếu có>",
    "order_code": "<mã đơn hàng nếu có>",
    "category": "<danh mục nếu có>"
  },
  "reason": "<giải thích ngắn>"
}
"""

async def classify_by_llm(question: str, user_id: str = None) -> dict:
    """
    Gọi Claude API để phân loại intent.
    Rẻ và nhanh vì chỉ gửi 1 câu hỏi ngắn — không đọc DB.
    """
    async with httpx.AsyncClient() as client:
        response = await client.post(
            "https://api.anthropic.com/v1/messages",
            headers={
                "x-api-key": "YOUR_API_KEY",
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": "claude-haiku-4-5-20251001",  # dùng Haiku: nhanh, rẻ cho classification
                "max_tokens": 256,
                "system": SYSTEM_PROMPT,
                "messages": [
                    {"role": "user", "content": question}
                ],
            },
            timeout=10.0,
        )

    data = response.json()
    raw_text = data["content"][0]["text"].strip()

    try:
        return json.loads(raw_text)
    except json.JSONDecodeError:
        # Fallback nếu parse lỗi
        return {"intent": "general", "confidence": 0.5, "extracted": {}, "reason": "parse error"}


# Ví dụ sử dụng (async)
import asyncio

async def main():
    questions = [
        "Cho tôi xem những đôi giày Adidas có size 42 không?",
        "Đơn hàng tôi đặt hôm qua sao chưa thấy ship?",
        "Mình muốn mua áo cho bạn trai, thích style sporty",
    ]

    for q in questions:
        result = await classify_by_llm(q)
        print(f"Q: {q}")
        print(f"   → {json.dumps(result, ensure_ascii=False, indent=2)}\n")

asyncio.run(main())
```

> **Lưu ý chi phí:** Claude Haiku xử lý ~100 token/lần phân loại, giá
> khoảng $0.00025/request. Với 10,000 câu hỏi/ngày chỉ tốn ~$2.5/ngày.

---

## Cách 3 — Hybrid: keyword trước, LLM sau (khuyên dùng)

Kết hợp tốc độ của keyword matching với độ chính xác của LLM.

```python
async def classify_intent(question: str, user_id: str = None) -> IntentResult:
    """
    Pipeline phân loại 2 bước:
    1. Thử keyword matching — nếu confident >= 0.7 thì dùng luôn
    2. Nếu không chắc, mới gọi LLM
    """

    # Bước 1: keyword matching
    keyword_result = classify_by_keywords(question)

    if keyword_result.confidence >= 0.7:
        print(f"[Keyword] {keyword_result.intent} ({keyword_result.confidence:.1f})")
        return keyword_result

    # Bước 2: gọi LLM vì không chắc
    print(f"[LLM fallback] keyword confidence chỉ {keyword_result.confidence:.1f}")
    llm_result = await classify_by_llm(question, user_id)

    return IntentResult(
        intent=llm_result["intent"],
        confidence=llm_result["confidence"],
        extracted=llm_result.get("extracted", {}),
    )
```

---

## Bước tiếp theo: query DB dựa trên intent

Sau khi có intent, chỉ query đúng bảng cần thiết:

```python
import psycopg2
from typing import Optional

def get_context_for_intent(
    intent: str,
    extracted: dict,
    user_id: Optional[str],
    conn  # psycopg2 connection
) -> str:
    """
    Trả về đoạn text ngắn (~300-500 tokens) để đưa vào prompt cho LLM.
    """
    cur = conn.cursor()

    if intent == "product_search":
        filters = ["p.status != 'DELETED'"]
        params = []

        if "brand" in extracted:
            filters.append("pr.code = %s")
            params.append(extracted["brand"])

        if "product_name" in extracted:
            filters.append("p.name ILIKE %s")
            params.append(f"%{extracted['product_name']}%")

        if "category" in extracted:
            filters.append("c.name ILIKE %s")
            params.append(f"%{extracted['category']}%")

        where = " AND ".join(filters)
        cur.execute(f"""
            SELECT p.name, p.price, p.status, pr.name as brand, c.name as category, p.rated
            FROM tbl_products p
            LEFT JOIN tbl_providers pr ON p.provider_id = pr.id
            LEFT JOIN tbl_categories c ON p.category_id = c.id
            WHERE {where}
            ORDER BY p.quantity_sold DESC
            LIMIT 5
        """, params)

        rows = cur.fetchall()
        if not rows:
            return "Không tìm thấy sản phẩm phù hợp."

        lines = ["Sản phẩm tìm được:"]
        for name, price, status, brand, cat, rated in rows:
            lines.append(f"- {name} ({brand}) | {price:,.0f}đ | {cat} | ⭐{rated or 'N/A'} | {status}")
        return "\n".join(lines)

    elif intent == "order_status" and user_id:
        order_code = extracted.get("order_code")

        if order_code:
            cur.execute("""
                SELECT o.code, o.status, o.final_price, o.created_at, o.payment_type
                FROM tbl_orders o
                WHERE o.user_id = %s AND o.code = %s
            """, (user_id, order_code))
        else:
            cur.execute("""
                SELECT o.code, o.status, o.final_price, o.created_at, o.payment_type
                FROM tbl_orders o
                WHERE o.user_id = %s
                ORDER BY o.created_at DESC
                LIMIT 3
            """, (user_id,))

        rows = cur.fetchall()
        if not rows:
            return "Không tìm thấy đơn hàng nào."

        lines = ["Đơn hàng của bạn:"]
        for code, status, price, created_at, payment in rows:
            lines.append(f"- {code} | {status} | {price:,.0f}đ | {payment} | {created_at.strftime('%d/%m/%Y')}")
        return "\n".join(lines)

    elif intent == "voucher_info" and user_id:
        cur.execute("""
            SELECT v.code, v."discountType", v.value, uv.end_at, uv.min_price_apply
            FROM tbl_user_vouchers uv
            JOIN tbl_vouchers v ON uv.voucher_id = v.id
            WHERE uv.user_id = %s AND uv.status = 'AVAILABLE'
            ORDER BY uv.end_at ASC
        """, (user_id,))

        rows = cur.fetchall()
        if not rows:
            return "Bạn hiện không có voucher khả dụng."

        lines = ["Voucher của bạn:"]
        for code, dtype, value, end_at, min_price in rows:
            discount_str = f"{value}%" if dtype == "PERCENT" else f"{value:,}đ"
            expire_str = end_at.strftime('%d/%m/%Y') if end_at else "Không giới hạn"
            lines.append(f"- {code}: giảm {discount_str} | đơn tối thiểu {min_price or 0:,.0f}đ | HSD: {expire_str}")
        return "\n".join(lines)

    elif intent == "general":
        return ""  # không cần DB, LLM trả lời từ system prompt

    return ""
```

---

## Ghép lại thành pipeline hoàn chỉnh

```python
CHATBOT_SYSTEM_PROMPT = """
Bạn là trợ lý mua sắm của shop thời trang. Trả lời thân thiện, ngắn gọn bằng tiếng Việt.

Nếu được cung cấp thông tin sản phẩm/đơn hàng/voucher từ DB, hãy dựa vào đó để trả lời.
Nếu không có thông tin, hãy hướng dẫn khách hàng liên hệ shop.

Chính sách shop:
- Giao hàng toàn quốc 2-5 ngày
- Đổi trả trong 7 ngày nếu lỗi nhà sản xuất
- Hotline: 1900-xxxx
"""

async def chatbot_response(
    question: str,
    user_id: Optional[str],
    conn
) -> str:
    # Bước 1: phân loại intent
    intent_result = await classify_intent(question, user_id)

    # Bước 2: lấy context từ DB (chỉ query đúng bảng)
    db_context = get_context_for_intent(
        intent=intent_result.intent,
        extracted=intent_result.extracted,
        user_id=user_id,
        conn=conn,
    )

    # Bước 3: gọi LLM với context nhỏ gọn
    user_message = question
    if db_context:
        user_message = f"{question}\n\n[Thông tin từ hệ thống]\n{db_context}"

    async with httpx.AsyncClient() as client:
        response = await client.post(
            "https://api.anthropic.com/v1/messages",
            headers={
                "x-api-key": "YOUR_API_KEY",
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": "claude-sonnet-4-6",
                "max_tokens": 512,
                "system": CHATBOT_SYSTEM_PROMPT,
                "messages": [{"role": "user", "content": user_message}],
            },
        )

    data = response.json()
    return data["content"][0]["text"]
```

---

## Tóm tắt luồng xử lý

```
User hỏi
    ↓
classify_intent()          ← chỉ tốn ~100 tokens, dùng Haiku
    ↓
get_context_for_intent()   ← query đúng bảng, trả về ≤10 dòng
    ↓
Claude Sonnet              ← nhận context nhỏ + câu hỏi → trả lời
    ↓
Trả về user
```

**Tổng token mỗi lần gọi:** ~150 (classification) + ~500 (answer) = ~650 tokens
**So với đẩy toàn bộ DB:** ~15,000+ tokens → tiết kiệm ~95%

---

## Cấu trúc thư mục gợi ý

```
chatbot/
├── classifier.py       # classify_by_keywords(), classify_by_llm(), classify_intent()
├── db_context.py       # get_context_for_intent() và các SQL query
├── chatbot.py          # chatbot_response() — pipeline chính
├── prompts.py          # SYSTEM_PROMPT, CHATBOT_SYSTEM_PROMPT
└── main.py             # FastAPI / Flask endpoint
```