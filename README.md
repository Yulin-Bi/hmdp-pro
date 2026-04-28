# hmdp 点评项目

基于 Spring Boot 构建的点评类应用，覆盖商铺浏览、用户登录、笔记发布、关注互动、优惠券和秒杀下单等核心业务，并集成了 Redis、MyBatis-Plus、RocketMQ、Redisson 以及 AI 聊天能力。

## 项目特点

- 用户登录与会话管理
- 商铺分类、商铺列表与详情查询
- 博客发布、评论、点赞与关注
- 优惠券与秒杀下单
- Redis 缓存、分布式锁与 ID 生成
- RocketMQ 异步消息处理
- AI 对话能力接入

## 技术栈

- Java 17
- Spring Boot 2.7.18
- MyBatis-Plus
- Redis
- MySQL
- RocketMQ
- Redisson
- Hutool
- Lombok

## 目录结构

- src/main/java/com/hmdp：后端业务代码
- src/main/resources/application.yaml：应用配置
- src/main/resources/db/hmdp.sql：数据库初始化脚本
- src/main/resources/seckill.lua：秒杀 Lua 脚本
- nginx-1.18.0/html/hmdp：前端静态页面资源

## 环境要求

- JDK 17
- Maven 3.8+
- MySQL 5.7/8.0
- Redis 6+
- RocketMQ 4.x/5.x
- Nginx 作为静态资源服务（可选，但建议配置）

## 本地运行

### 1. 准备数据库

创建名为 hmdp 的数据库，并导入初始化脚本：

```sql
source src/main/resources/db/hmdp.sql;
```

### 2. 修改配置

根据本机环境调整 src/main/resources/application.yaml：

- MySQL 地址、用户名和密码
- Redis 地址与端口
- RocketMQ NameServer 地址
- AI 服务 api-key

### 3. 启动后端

```bash
mvn clean package
mvn spring-boot:run
```

默认端口为 8086。

### 4. 部署前端静态页

将 nginx-1.18.0/html/hmdp 下的页面资源放到 Nginx 的站点目录中，并按需配置反向代理到后端接口。

## 接口模块

- /user：用户相关接口
- /shop：商铺查询接口
- /shop-type：商铺分类接口
- /blog：博客接口
- /blog-comments：博客评论接口
- /follow：关注接口
- /voucher：优惠券接口
- /voucher-order：秒杀订单接口
- /upload：文件上传接口
- /ai：AI 聊天接口

## 配置说明

当前配置文件中的 AI、数据库、Redis 和 RocketMQ 信息都以本地环境为默认值，提交到 GitHub 前建议确认这些敏感信息是否需要替换为占位符。

## 说明

该项目适合作为学习 Redis 缓存、秒杀系统、分布式锁以及 Spring Boot 业务开发的练手项目。