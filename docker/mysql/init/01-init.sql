DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS seckill_goods;
DROP TABLE IF EXISTS goods;
DROP TABLE IF EXISTS `user`;

CREATE TABLE goods (
                       id BIGINT NOT NULL,
                       name VARCHAR(200) NOT NULL,
                       price DECIMAL(10,2) NOT NULL,
                       stock INT NOT NULL,
                       detail TEXT NULL,
                       create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                       PRIMARY KEY (id)
);

INSERT INTO goods (id, name, price, stock, detail, create_time) VALUES
                                                                    (11, 'Product1', 19.99, 100, 'Description of product 1', '2026-03-30 12:00:00'),
                                                                    (12, 'Product2', 29.99, 200, 'Description of product 2', '2026-03-30 12:30:00'),
                                                                    (13, 'Product3', 15.49, 150, 'Description of product 3', '2026-03-30 13:00:00'),
                                                                    (14, 'Product4', 49.99, 50, 'Description of product 4', '2026-03-30 13:30:00'),
                                                                    (1001, 'Product 1', 99.99, 200, 'Description of Product 1', '2026-03-01 10:00:00'),
                                                                    (1002, 'Product 2', 149.50, 150, 'Description of Product 2', '2026-03-01 10:00:00'),
                                                                    (1003, 'Product 3', 59.90, 300, 'Description of Product 3', '2026-03-01 10:00:00'),
                                                                    (1004, 'Product 4', 199.00, 100, 'Description of Product 4', '2026-03-01 10:00:00'),
                                                                    (1005, 'Product 5', 79.99, 200, 'Description of Product 5', '2026-03-01 10:00:00');

CREATE TABLE seckill_goods (
                               id BIGINT NOT NULL AUTO_INCREMENT,
                               goods_id BIGINT NOT NULL,
                               seckill_price DECIMAL(10,2) NOT NULL,
                               seckill_stock INT NOT NULL,
                               start_time DATETIME NOT NULL,
                               end_time DATETIME NOT NULL,
                               PRIMARY KEY (id),
                               KEY idx_goods_id (goods_id),
                               KEY idx_start_time (start_time)
);

INSERT INTO seckill_goods (id, goods_id, seckill_price, seckill_stock, start_time, end_time) VALUES
                                                                                                 (1, 1001, 99.99, 50, '2026-04-22 19:00:00', '2026-04-22 20:00:00'),
                                                                                                 (2, 1002, 149.50, 29, '2026-04-22 19:00:00', '2026-04-22 20:00:00'),
                                                                                                 (3, 1003, 59.90, 100, '2026-04-22 19:00:00', '2026-04-22 20:00:00'),
                                                                                                 (4, 1004, 199.00, 20, '2026-04-22 19:00:00', '2026-04-22 20:00:00'),
                                                                                                 (5, 1005, 79.99, 75, '2026-04-22 19:00:00', '2026-04-22 20:00:00');

CREATE TABLE `user` (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        username VARCHAR(50) NOT NULL,
                        password VARCHAR(100) NOT NULL,
                        phone VARCHAR(20) DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_username (username)
);

CREATE TABLE `order` (
                         id BIGINT NOT NULL AUTO_INCREMENT,
                         order_no VARCHAR(32) NOT NULL,
                         user_id BIGINT NOT NULL,
                         goods_id BIGINT NOT NULL,
                         goods_name VARCHAR(200) DEFAULT NULL,
                         goods_price DECIMAL(10,2) NOT NULL,
                         quantity INT NOT NULL,
                         total_amount DECIMAL(10,2) NOT NULL,
                         status TINYINT NOT NULL DEFAULT 0,
                         create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                         PRIMARY KEY (id),
                         UNIQUE KEY uk_order_no (order_no),
                         KEY idx_user_id (user_id)
);