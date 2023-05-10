# 前提

ShardingSphere可以理解为ShardingJDBC的演进版，除了提供原有ShardingJDBC客户端级别的分库分表功能外，还提供了ShardingProxy这种中间件级别的组件（类似MyCat），接下来回顾一下ShardingJDBC的功能。

# 读写分离

```yaml
spring:
  application:
    name: demo_a
  shardingsphere:
    mode:
      type: Memory # 集群元数据使用内存存储
    datasource:
      # 这个集群的节点名称（自定义）
      names: master,slave01,slave02
      master: # 节点名称对应的数据库配置
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://192.168.120.161:3006/business_a?allowPublicKeyRetrieval=true
        username: root
        password: 123456
      slave01:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://192.168.120.161:3005/business_a?allowPublicKeyRetrieval=true
        username: root
        password: 123456
      slave02:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://192.168.120.161:3007/business_a?allowPublicKeyRetrieval=true
        username: root
        password: 123456
    rules:
      readwrite-splitting:
        data-sources:
          my-datasource: # 读写分离数据源的名字，可以包含上面定义的多个数据源
            type: Static
            props:
              # 写数据源
              write-data-source-name: master
              # 读数据源
              read-data-source-names: slave01,slave02
              # 读数据源的负载均衡算法名称，可以自定义
            load-balancer-name: aaa_round
        load-balancers:
          aaa_round:
            # 配置aaa_round的负载均衡算法为轮询
            type: ROUND_ROBIN
    props:
      # 打印sql
      sql-show: true
```

执行com.genn.A.ReadWriteSplitTest#readWriteSplit测试用例，发现正常读写分离，且读操作采用轮询负载均衡：

![image-20230507193404013](01-ShardingSphere之ShardingJDBC.assets/01.png)

# 水平分片

准备好`多表DDL`：

```sql
CREATE TABLE table_b0 (
	id BIGINT,
	b_name VARCHAR(30),
	b_status INT,
	PRIMARY KEY (id)
);

CREATE TABLE table_b1 (
	id BIGINT,
	b_name VARCHAR(30),
	b_status INT,
	PRIMARY KEY (id)
);
```

```java
@TableName("table_b")
public class TableB {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String bName;
    private Integer bStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getbName() {
        return bName;
    }

    public void setbName(String bName) {
        this.bName = bName;
    }

    public Integer getbStatus() {
        return bStatus;
    }

    public void setbStatus(Integer bStatus) {
        this.bStatus = bStatus;
    }
}
```

对于shardingjdbc来说，物理表又称为数据节点，细分粒度为主机+数据库+表。比如在这个例子中，table_b有两个数据节点，分别是`master机的business_a库的table_b0表`和`master机的business_a库的table_b1表`。需要在配置文件中声明 逻辑表 与 数据节点的关系：

```yaml
spring:
  shardingsphere:
    rules:
      sharding-tables: 
        table_b:
          # table_b的逻辑表与数据节点的映射关系
          actual-data-nodes: master.table_b0,master.table_b1
```

## 哈希取模

配置好逻辑表与数据节点的映射后，开始配置 逻辑表 到  数据节点的分片算法：

```yaml
spring:
  shardingsphere:
    rules:
      sharding:
        tables:
          table_b:
            # table_b的逻辑表与数据节点的映射关系
            actual-data-nodes: master.table_b0,master.table_b1
            table-strategy:
              standard:
                sharding-column: id
                sharding-algorithm-name: id_hash_mod
        sharding-algorithms:
          id_hash_mod:
            type: HASH_MOD
            props:
              sharding-count: 2
```

上面配置了tableB以id为分片字段，通过hash后 % 2 的方式定位数据节点。

准备测试用例：

```java
@Autowired
private TableBMapper tableBMapper;

@Test
public void testInsert() {
    for (Integer i = 0; i < 5; i++) {
        TableB b = new TableB();
        b.setId(Long.valueOf(i));
        b.setbName(String.valueOf(i));
        b.setbStatus(i);
        tableBMapper.insert(b);
    }
}
```

最终结果：

```sql
mysql> select * from table_b0;
+----+--------+----------+
| id | b_name | b_status |
+----+--------+----------+
|  0 | 0      |        0 |
|  2 | 2      |        2 |
|  4 | 4      |        4 |
+----+--------+----------+
3 rows in set (0.00 sec)

mysql> select * from table_b1;
+----+--------+----------+
| id | b_name | b_status |
+----+--------+----------+
|  1 | 1      |        1 |
|  3 | 3      |        3 |
+----+--------+----------+
2 rows in set (0.00 sec)
```

## 关联表

假设 借款表t_borrow 与 还款计划表t_plan，两表通过borrow_id进行关联，同时也通过borrow_id作为分片条件进行分表，当查询 SELECT * FROM t_borrow borrow LEFT JOIN  t_plan plan ON borrow.borrow_id = plan.borrow_id WHERE borrow.borrow_id = 10000时，ShardingSphere会先根据borrow_id = 10000定位到t_borrow所在的数据节点，然后**采用笛卡尔积的方式** LEFT JOIN 所有 t_plan的数据节点。

但是，t_borrow和t_plan都采用borrow_id进行分片，当确定某个borrow_id值时，t_borrow和t_plan的记录都应该是**可定位的**，采用笛卡尔积的方式会产生多余的查询语句，增加查询耗时。因此，可以配置t_borrow和t_plan的**关联表关系**，当这两个表进行join操作、**且join条件同样是分片条件时**，ShardingSphere会先分片，再Join。

用过，不再赘述配置方式和使用过程，具体看文档：https://shardingsphere.apache.org/document/current/cn/user-manual/shardingsphere-jdbc/yaml-config/

## 广播表

主要是用于字典表或公共配置表，对于这种表如果采用分片方式存储的话，配置成广播表能新增以下特性：

1. 对于广播表的写操作，ShardingSphere会采用全局写入的方式，写入所有数据节点。
2. 对于广播表的读操作，ShardingSphere只会读取某一个数据节点的数据，采用就近原则。

生产上主要使用公共字典服务的方式管理字典表，不再赘述广播表的配置方式和使用过程，具体看文档：https://shardingsphere.apache.org/document/current/cn/user-manual/shardingsphere-jdbc/yaml-config/

# 读写分离与水平分片混用！！！

将 `数据节点的数据源` 配置成 `读写分离的自定义数据源即可`：

```yaml
spring:
  shardingsphere:
    rules:
      sharding:
        tables:
          table_b:
            # 数据节点的数据源
            actual-data-nodes: my-datasource.table_b0,my-datasource.table_b1
            table-strategy:
              standard:
                sharding-column: id
                sharding-algorithm-name: id_hash_mod
        sharding-algorithms:
          id_hash_mod:
            type: HASH_MOD
            props:
              sharding-count: 2
      readwrite-splitting:
        data-sources:
          # 读写分离的自定义数据源
          my-datasource:
            type: Static
            props:
              write-data-source-name: master
              read-data-source-names: slave01,slave02
            load-balancer-name: aaa_round
        load-balancers:
          aaa_round:
            type: ROUND_ROBIN
```

测试用例：

```java
    @Test
    public void testRSAndRWS(){
        TableB rSAndRWS = new TableB();
        rSAndRWS.setId(10L);
        rSAndRWS.setbStatus(10);
        rSAndRWS.setbName("10");
        tableBMapper.insert(rSAndRWS);

        tableBMapper.selectById(10);

        tableBMapper.selectList(new QueryWrapper<TableB>());
    }
```

日志：

```
### 写单条
2023-05-10 13:11:33.597  INFO 680351 --- [           main] ShardingSphere-SQL                       : Logic SQL: INSERT INTO table_b  ( id,
b_name,
b_status )  VALUES  ( ?,
?,
? )
2023-05-10 13:11:33.597  INFO 680351 --- [           main] ShardingSphere-SQL                       : SQLStatement: MySQLInsertStatement(setAssignment=Optional.empty, onDuplicateKeyColumns=Optional.empty)
2023-05-10 13:11:33.598  INFO 680351 --- [           main] ShardingSphere-SQL                       : Actual SQL: master ::: INSERT INTO table_b0  ( id,
b_name,
b_status )  VALUES  (?, ?, ?) ::: [10, 10, 10]






### 查询单条（分片字段）
2023-05-10 13:11:33.841  INFO 680351 --- [           main] ShardingSphere-SQL                       : Logic SQL: SELECT id,b_name,b_status FROM table_b WHERE id=? 
2023-05-10 13:11:33.841  INFO 680351 --- [           main] ShardingSphere-SQL                       : SQLStatement: MySQLSelectStatement(table=Optional.empty, limit=Optional.empty, lock=Optional.empty, window=Optional.empty)
2023-05-10 13:11:33.841  INFO 680351 --- [           main] ShardingSphere-SQL                       : Actual SQL: slave01 ::: SELECT id,b_name,b_status FROM table_b0 WHERE id=?  ::: [10]






### 查询单条（非分片字段）
2023-05-10 13:26:57.309  INFO 724595 --- [           main] ShardingSphere-SQL                       : Logic SQL: SELECT  id,b_name,b_status  FROM table_b WHERE (b_status = ?)
2023-05-10 13:26:57.310  INFO 724595 --- [           main] ShardingSphere-SQL                       : SQLStatement: MySQLSelectStatement(table=Optional.empty, limit=Optional.empty, lock=Optional.empty, window=Optional.empty)
2023-05-10 13:26:57.310  INFO 724595 --- [           main] ShardingSphere-SQL                       : Actual SQL: slave01 ::: SELECT  id,b_name,b_status  FROM table_b0 WHERE (b_status = ?) ::: [10]
2023-05-10 13:26:57.310  INFO 724595 --- [           main] ShardingSphere-SQL                       : Actual SQL: slave02 ::: SELECT  id,b_name,b_status  FROM table_b1 WHERE (b_status = ?) ::: [10]






### 查询多条
2023-05-10 13:11:33.902  INFO 680351 --- [           main] ShardingSphere-SQL                       : Logic SQL: SELECT  id,b_name,b_status  FROM table_b
2023-05-10 13:11:33.903  INFO 680351 --- [           main] ShardingSphere-SQL                       : SQLStatement: MySQLSelectStatement(table=Optional.empty, limit=Optional.empty, lock=Optional.empty, window=Optional.empty)
2023-05-10 13:11:33.903  INFO 680351 --- [           main] ShardingSphere-SQL                       : Actual SQL: slave02 ::: SELECT  id,b_name,b_status  FROM table_b0
2023-05-10 13:11:33.903  INFO 680351 --- [           main] ShardingSphere-SQL                       : Actual SQL: slave01 ::: SELECT  id,b_name,b_status  FROM table_b1
```

