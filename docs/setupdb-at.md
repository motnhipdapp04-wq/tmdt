# Setup Materialized View cho AI chatbot

## Tổng quan

Tạo các Materialized View trong cùng DB, chia 2 nhóm refresh:

| Nhóm     | Bảng                                                            | Refresh              | Lý do                          |
|----------|-----------------------------------------------------------------|----------------------|--------------------------------|
| **Slow** | products, categories, providers, promotions, vouchers, comments | 1 lần/ngày (2:00 AM) | Data ít thay đổi               |
| **Fast** | items (tồn kho)                                                 | Mỗi giờ              | Tồn kho thay đổi theo đơn hàng |

AI chỉ có quyền `SELECT` trên các view này — không thể đọc bảng gốc.

---

## Bước 1 — Bật extension pg_cron

```sql
-- Chạy với quyền superuser
CREATE EXTENSION IF NOT EXISTS pg_cron;
```

Kiểm tra đã cài chưa:

```sql
SELECT *
FROM pg_extension
WHERE extname = 'pg_cron';
```

> **Lưu ý:** pg_cron cần được thêm vào `postgresql.conf`:
> ```
> shared_preload_libraries = 'pg_cron'
> cron.database_name = 'your_database_name'
> ```
> Sau đó restart PostgreSQL.

---

## Bước 2 — Tạo schema riêng cho AI

```sql
-- Schema riêng biệt, tách hoàn toàn khỏi public
CREATE SCHEMA IF NOT EXISTS ai_view;
```

---

## Bước 3 — Nhóm SLOW (refresh 1 lần/ngày)

### 3.1 Sản phẩm

```sql
CREATE MATERIALIZED VIEW ai_view.products AS
SELECT p.id,
       p.name,
       p.code,
       p.description,
       p.price,
       p.status,
       p.rated,
       p.quantity_sold,
       p.img,
       c.name  AS category_name,
       c.code  AS category_code,
       pr.name AS provider_name,
       pr.code AS provider_code
FROM public.tbl_products p
         LEFT JOIN public.tbl_categories c ON p.category_id = c.id
         LEFT JOIN public.tbl_providers pr ON p.provider_id = pr.id
WHERE p.status != 'DELETED'
WITH DATA;

CREATE INDEX ON ai_view.products (status);
CREATE INDEX ON ai_view.products (provider_code);
CREATE INDEX ON ai_view.products (category_code);
```

### 3.2 Danh mục

```sql
CREATE MATERIALIZED VIEW ai_view.categories AS
SELECT id,
       name,
       code,
       description,
       status,
       level,
       parent_id
FROM public.tbl_categories
WHERE status = 'ACTIVE'
WITH DATA;
```

### 3.3 Thương hiệu / nhà cung cấp

```sql
CREATE MATERIALIZED VIEW ai_view.providers AS
SELECT id,
       name,
       code,
       description,
       status
FROM public.tbl_providers
WHERE status != 'INACTIVE'
WITH DATA;
```

### 3.4 Khuyến mãi đang chạy

```sql
CREATE MATERIALIZED VIEW ai_view.promotions AS
SELECT id,
       value,
       start_at,
       end_at,
       scope,
       status
FROM public.tbl_promotions
WHERE status = 'ACTIVE'
  AND end_at > NOW()
WITH DATA;
```

### 3.5 Voucher công khai

```sql
-- Chỉ expose voucher GLOBAL, không expose voucher cá nhân (NEWBIE)
CREATE MATERIALIZED VIEW ai_view.vouchers AS
SELECT id,
       code,
       "discountType",
       value,
       min_order_amount,
       start_at,
       end_at,
       status
FROM public.tbl_vouchers
WHERE status = 'ACTIVE'
  AND "voucherType" = 'GLOBAL'
  AND (end_at IS NULL OR end_at > NOW())
WITH DATA;
```

### 3.6 Review sản phẩm (tổng hợp, không lộ user_id)

```sql
-- Chỉ expose nội dung review, ẩn hoàn toàn user_id
CREATE MATERIALIZED VIEW ai_view.product_reviews AS
SELECT c.product_id,
       p.name AS product_name,
       c.content,
       c.rating,
       c.created_at
FROM public.tbl_comments c
         JOIN public.tbl_products p ON c.product_id = p.id
WITH DATA;

CREATE INDEX ON ai_view.product_reviews (product_id);
```

---

## Bước 4 — Nhóm FAST (refresh mỗi giờ)

### 4.1 Tồn kho

```sql
-- Gộp luôn thông tin size vào, AI không cần join thêm
CREATE MATERIALIZED VIEW ai_view.inventory AS
SELECT i.product_id,
       p.name AS product_name,
       s.size,
       i.quantity,
       i.status
FROM public.tbl_items i
         JOIN public.tbl_products p ON i.product_id = p.id
         JOIN public.tbl_sizes s ON i.size_id = s.id
WHERE i.status = 'AVAILABLE'
WITH DATA;

CREATE INDEX ON ai_view.inventory (product_id);
CREATE INDEX ON ai_view.inventory (status);
```

---

## Bước 5 — Tạo role ai_reader

```sql
-- Tạo role chỉ đọc, không có password login trực tiếp
CREATE ROLE ai_reader;

-- Chỉ cho phép đọc schema ai_view
GRANT USAGE ON SCHEMA ai_view TO ai_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA ai_view TO ai_reader;

-- Đảm bảo view tạo mới trong tương lai cũng được cấp quyền
ALTER DEFAULT PRIVILEGES IN SCHEMA ai_view
    GRANT SELECT ON TABLES TO ai_reader;

-- Tạo user thực sự dùng role này
CREATE USER ai_bot WITH PASSWORD 'your_strong_password_here';
GRANT ai_reader TO ai_bot;

-- Chắc chắn ai_bot KHÔNG có quyền đọc schema public
REVOKE ALL ON SCHEMA public FROM ai_bot;
```

Kiểm tra quyền:

```sql
-- Xem ai_bot có thể đọc gì
SELECT table_schema, table_name, privilege_type
FROM information_schema.role_table_grants
WHERE grantee = 'ai_reader';
```

---

## Bước 6 — Setup pg_cron

```sql
-- Refresh nhóm SLOW: 2:00 AM mỗi ngày
SELECT cron.schedule('refresh-ai-slow', '0 2 * * *', $$
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.products;
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.categories;
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.providers;
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.promotions;
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.vouchers;
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.product_reviews;
$$);

-- Refresh nhóm FAST: đầu mỗi giờ
SELECT cron.schedule('refresh-ai-fast', '0 * * * *', $$
    REFRESH MATERIALIZED VIEW CONCURRENTLY ai_view.inventory;
$$);
```

> **Lưu ý:** `CONCURRENTLY` cho phép AI vẫn đọc được view trong lúc refresh — không bị lock. Yêu cầu view phải có ít
> nhất 1 unique index.

Thêm unique index để dùng CONCURRENTLY:

```sql
-- products cần unique index trên id
CREATE UNIQUE INDEX ON ai_view.products (id);
CREATE UNIQUE INDEX ON ai_view.categories (id);
CREATE UNIQUE INDEX ON ai_view.providers (id);
CREATE UNIQUE INDEX ON ai_view.promotions (id);
CREATE UNIQUE INDEX ON ai_view.vouchers (id);
CREATE UNIQUE INDEX ON ai_view.inventory (product_id, size);

-- product_reviews không có PK rõ ràng → dùng composite
CREATE UNIQUE INDEX ON ai_view.product_reviews (product_id, content);
```

Xem lịch đã đặt:

```sql
SELECT jobid, schedule, command
FROM cron.job;
```

Xem lịch sử chạy:

```sql
SELECT *
FROM cron.job_run_details
ORDER BY start_time DESC
LIMIT 10;
```

---

## Bước 7 — Kết nối từ Python

```python
import psycopg2

# Dùng user ai_bot — chỉ đọc được ai_view
AI_DB_CONFIG = {
    "host":     "localhost",
    "port":     5432,
    "dbname":   "your_database_name",
    "user":     "ai_bot",
    "password": "your_strong_password_here",
    "options":  "-c search_path=ai_view",  # mặc định tìm trong ai_view
}

def get_ai_connection():
    return psycopg2.connect(**AI_DB_CONFIG)


def search_products(keyword: str = None, brand_code: str = None, category_code: str = None):
    conn = get_ai_connection()
    cur = conn.cursor()

    filters = ["status != 'OUT_OF_STOCK'"]
    params = []

    if keyword:
        filters.append("name ILIKE %s")
        params.append(f"%{keyword}%")

    if brand_code:
        filters.append("provider_code = %s")
        params.append(brand_code)

    if category_code:
        filters.append("category_code = %s")
        params.append(category_code)

    where = " AND ".join(filters)

    # Không cần viết schema vì search_path đã set = ai_view
    cur.execute(f"""
        SELECT name, price, status, provider_name, category_name, rated
        FROM products
        WHERE {where}
        ORDER BY quantity_sold DESC
        LIMIT 5
    """, params)

    rows = cur.fetchall()
    conn.close()

    if not rows:
        return "Không tìm thấy sản phẩm phù hợp."

    lines = ["Sản phẩm tìm được:"]
    for name, price, status, brand, cat, rated in rows:
        lines.append(f"- {name} ({brand}) | {price:,.0f}đ | {cat} | ⭐{rated or 'N/A'}")
    return "\n".join(lines)


def check_inventory(product_name: str, size: str = None):
    conn = get_ai_connection()
    cur = conn.cursor()

    filters = ["product_name ILIKE %s", "status = 'AVAILABLE'"]
    params = [f"%{product_name}%"]

    if size:
        filters.append("size = %s")
        params.append(size.upper())

    where = " AND ".join(filters)

    cur.execute(f"""
        SELECT product_name, size, quantity
        FROM inventory
        WHERE {where}
        ORDER BY size
    """, params)

    rows = cur.fetchall()
    conn.close()

    if not rows:
        return "Sản phẩm này hiện đang hết hàng."

    lines = []
    for product_name, size, qty in rows:
        status = "còn hàng" if qty > 10 else "sắp hết"
        lines.append(f"- Size {size}: {status} ({qty} cái)")
    return f"{rows[0][0]}\n" + "\n".join(lines)
```

---

## Tóm tắt kiến trúc

```
PostgreSQL (1 DB duy nhất)
│
├── schema: public          ← DB gốc, app backend đọc/ghi bình thường
│   ├── tbl_products
│   ├── tbl_orders          ← AI không có quyền đọc
│   ├── tbl_accounts        ← AI không có quyền đọc
│   └── ...
│
└── schema: ai_view         ← AI chỉ đọc được đây
    ├── products            ┐
    ├── categories          │ refresh 2:00 AM/ngày
    ├── providers           │
    ├── promotions          │
    ├── vouchers            │
    ├── product_reviews     ┘
    │
    └── inventory           ← refresh mỗi giờ


PostgreSQL role
├── ai_bot (user)           ← chatbot dùng user này để connect
│   └── ai_reader (role)    ← chỉ SELECT trên schema ai_view
│
└── app_user (user)         ← backend bình thường, full quyền public
```

---

## Checklist triển khai

- [ ] Bật `pg_cron` và restart PostgreSQL
- [ ] Chạy SQL tạo schema `ai_view`
- [ ] Chạy SQL tạo các Materialized View (nhóm slow + fast)
- [ ] Tạo unique index cho từng view
- [ ] Tạo role `ai_reader` và user `ai_bot`
- [ ] Đặt lịch pg_cron cho 2 nhóm
- [ ] Test kết nối Python với `ai_bot` — thử đọc `public.tbl_orders` phải bị từ chối
- [ ] Refresh thủ công lần đầu: `REFRESH MATERIALIZED VIEW ai_view.products;`