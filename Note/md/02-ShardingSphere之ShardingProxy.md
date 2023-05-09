#   前提

ShardingProxy作为中间件级别的分库分表组件，应用程序把它当做一个MySQL单点客户端直接连接即可，无需ShardingJDBC的依赖，只不过逻辑语句和物理语句需要在ShardingProxy的日志上查看。这里就不演示程序连接了，**实际生产使用要注意链路追踪与ShardingProxy的整合**。

# Docker搭建ShardingProxy

```bash
# 准备挂载目录
root@kjg-PC:~# mkdir -p /root/sharding-proxy/proxy-a/conf /root/sharding-proxy/proxy-a/ext-lib

# 启动容器
root@kjg-PC:~# docker run -d \
> -v /root/sharding-proxy/proxy-a/conf:/opt/shardingsphere-proxy/conf \
> -v /root/sharding-proxy/proxy-a/ext-lib:/opt/shardingsphere-proxy/ext-lib \
> -e JVM_OPTS="-Xmx256m -Xms256m -Xmn128m" \
> -p 3307:3307 \
> --name sharding-proxy-a \
> apache/shardingsphere-proxy:5.1.1
Unable to find image 'apache/shardingsphere-proxy:5.1.1' locally
5.1.1: Pulling from apache/shardingsphere-proxy
a2abf6c4d29d: Already exists 
2bbde5250315: Pull complete 
115191490c27: Pull complete 
61b680ac8083: Pull complete 
153c361c7d29: Pull complete 
Digest: sha256:29fdeb1aae9b01181628caf118d1af094e6a10925b3b4860e1ecb101904cb973
Status: Downloaded newer image for apache/shardingsphere-proxy:5.1.1
fd20687a6bb14f5bee0bbdfcaa687a745068cd8c941c9741ed8afead947e6660

# 复制依赖
root@kjg-PC:~# cp /home/kjg/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar /root/sharding-proxy/proxy-a/ext-lib/
```

修改conf/server.yaml，打开以下配置**（注意！！！！root@%:123456是字符串）**

```yaml
rules:
  users:
    - root@%:123456
  provider:
    type: ALL_PRIVILEGES_PERMITTED
props:
  sql-show: true
```

重启容器，mysql连接，发现成功连上ShardingProxy：

```bash
root@kjg-PC:~/sharding-proxy/proxy-a/conf# docker restart sharding-proxy-a


root@kjg-PC:~/sharding-proxy/proxy-a/conf# mysql -uroot -p123456 -h 192.168.120.161 -P 3307
mysql: [Warning] Using a password on the command line interface can be insecure.
Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 1
Server version: 5.7.22-ShardingSphere-Proxy 5.1.1 

Copyright (c) 2000, 2022, Oracle and/or its affiliates.

Oracle is a registered trademark of Oracle Corporation and/or its
affiliates. Other names may be trademarks of their respective
owners.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql>
```

# 读写分离

修改conf/config-readwrite-splitting.yaml：

```yaml
schemaName: readwrite_splitting_db

dataSources:
  write_ds:
    url: jdbc:mysql://192.168.120.161:3006/business_a?serverTimezone=UTC&useSSL=false
    username: root
    password: 123456
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
  read_ds_0:
    url: jdbc:mysql://192.168.120.161:3005/business_a?serverTimezone=UTC&useSSL=false
    username: root
    password: 123456
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
  read_ds_1:
    url: jdbc:mysql://192.168.120.161:3007/business_a?serverTimezone=UTC&useSSL=false
    username: root
    password: 123456
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1

rules:
- !READWRITE_SPLITTING
  dataSources:
   readwrite_ds:
      type: Static
      props:
        write-data-source-name: write_ds
        read-data-source-names: read_ds_0,read_ds_1
```

重启容器，查看容器内日志：

```bash
root@kjg-PC:~/sharding-proxy/proxy-a/conf# docker restart sharding-proxy-a
sharding-proxy-a
root@kjg-PC:~/sharding-proxy/proxy-a/conf# docker exec -it sharding-proxy-a /bin/bash


root@d5522e63044a:/# tail -f /opt/shardingsphere-proxy/logs/stdout.log 
Thanks for using Atomikos! This installation is not registered yet. 
REGISTER FOR FREE at http://www.atomikos.com/Main/RegisterYourDownload and receive:
- tips & advice 
- working demos 
- access to the full documentation 
- special exclusive bonus offers not available to others 
- everything you need to get the most out of using Atomikos!
[INFO ] 2023-05-09 05:06:02.838 [main] o.a.s.p.i.BootstrapInitializer - listeners.keySet=[RULE_ALTERED_JOB_WORKER]
[INFO ] 2023-05-09 05:06:02.902 [main] o.a.s.p.v.ShardingSphereProxyVersion - Database name is `MySQL`, version is `8.0.29`
[INFO ] 2023-05-09 05:06:03.232 [main] o.a.s.p.frontend.ShardingSphereProxy - ShardingSphere-Proxy Memory mode started successfully
```

新建一个ssh窗口，连接ShardingProxy，查看库表信息是否一致（**上面配置了逻辑库名叫readwrite_splitting_db**）：

```sql
kjg@kjg-PC:~$ mysql -uroot -p123456 -h192.168.120.161 -P3307
mysql: [Warning] Using a password on the command line interface can be insecure.
Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 2
Server version: 8.0.29-ShardingSphere-Proxy 5.1.1 MySQL Community Server - GPL

Copyright (c) 2000, 2022, Oracle and/or its affiliates.

Oracle is a registered trademark of Oracle Corporation and/or its
affiliates. Other names may be trademarks of their respective
owners.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql> show databases;
+------------------------+
| schema_name            |
+------------------------+
| readwrite_splitting_db |
| mysql                  |
| information_schema     |
| performance_schema     |
| sys                    |
+------------------------+
5 rows in set (0.01 sec)

mysql> use readwrite_splitting_db
Reading table information for completion of table and column names
You can turn off this feature to get a quicker startup with -A

Database changed
mysql> show tables;
+----------------------------------+------------+
| Tables_in_readwrite_splitting_db | Table_type |
+----------------------------------+------------+
| table_a                          | BASE TABLE |
| table_b1                         | BASE TABLE |
| table_b0                         | BASE TABLE |
+----------------------------------+------------+
3 rows in set (0.01 sec)
```

测试读写分离，查看ShardingProxy的日志：

```
### ShardingProxy查询：
mysql> select * from table_a;
+----+----------------+----------+
| id | a_name         | a_status |
+----+----------------+----------+
|  1 | hello1         |        1 |
|  2 | hello2         |        1 |
|  4 | inTransaction1 |        2 |
|  5 | inTransaction2 |        2 |
|  7 | inTransaction3 |        2 |
|  8 | updatefirst    |        2 |
|  9 | insert         |        3 |
+----+----------------+----------+
7 rows in set (0.15 sec)

### ShardingProxy日志：
[INFO ] 2023-05-09 05:15:05.668 [ShardingSphere-Command-1] ShardingSphere-SQL - Logic SQL: select * from table_a
[INFO ] 2023-05-09 05:15:05.668 [ShardingSphere-Command-1] ShardingSphere-SQL - SQLStatement: MySQLSelectStatement(table=Optional.empty, limit=Optional.empty, lock=Optional.empty, window=Optional.empty)
[INFO ] 2023-05-09 05:15:05.668 [ShardingSphere-Command-1] ShardingSphere-SQL - Actual SQL: read_ds_0 ::: select * from table_a





### ShardingProxy插入：
mysql> INSERT INTO table_a(a_name,a_status) VALUES ('sharding-proxy-insert-test',4);
Query OK, 1 row affected (0.24 sec)

### ShardingProxy日志：
[INFO ] 2023-05-09 05:16:45.371 [ShardingSphere-Command-2] ShardingSphere-SQL - Logic SQL: INSERT INTO table_a(a_name,a_status) VALUES ('sharding-proxy-insert-test',4)
[INFO ] 2023-05-09 05:16:45.371 [ShardingSphere-Command-2] ShardingSphere-SQL - SQLStatement: MySQLInsertStatement(setAssignment=Optional.empty, onDuplicateKeyColumns=Optional.empty)
[INFO ] 2023-05-09 05:16:45.371 [ShardingSphere-Command-2] ShardingSphere-SQL - Actual SQL: write_ds ::: INSERT INTO table_a(a_name,a_status) VALUES ('sharding-proxy-insert-test',4)





### ShardingProxy第二次查询：
mysql> select * from table_a;
+----+----------------------------+----------+
| id | a_name                     | a_status |
+----+----------------------------+----------+
|  1 | hello1                     |        1 |
|  2 | hello2                     |        1 |
|  4 | inTransaction1             |        2 |
|  5 | inTransaction2             |        2 |
|  7 | inTransaction3             |        2 |
|  8 | updatefirst                |        2 |
|  9 | insert                     |        3 |
| 10 | sharding-proxy-insert-test |        4 |
+----+----------------------------+----------+
8 rows in set (0.04 sec)

### ShardingProxy日志：
[INFO ] 2023-05-09 05:18:40.501 [ShardingSphere-Command-3] ShardingSphere-SQL - Logic SQL: select * from table_a
[INFO ] 2023-05-09 05:18:40.502 [ShardingSphere-Command-3] ShardingSphere-SQL - SQLStatement: MySQLSelectStatement(table=Optional.empty, limit=Optional.empty, lock=Optional.empty, window=Optional.empty)
[INFO ] 2023-05-09 05:18:40.502 [ShardingSphere-Command-3] ShardingSphere-SQL - Actual SQL: read_ds_1 ::: select * from table_a
```

可以看到，读写分离 和 读负载均衡都生效了。

# 水平分片

修改conf/config-read-write-spliting.yaml（和上面的读写分离采用同一个逻辑库）：

```yaml
schemaName: readwrite_splitting_db

dataSources:
  write_ds:
    url: jdbc:mysql://192.168.120.161:3006/business_a?serverTimezone=UTC&useSSL=false
    username: root
    password: 123456
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
  read_ds_0:
    url: jdbc:mysql://192.168.120.161:3005/business_a?serverTimezone=UTC&useSSL=false
    username: root
    password: 123456
    connectionTimeoutMilliseconds: 30000    
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1
  read_ds_1:
    url: jdbc:mysql://192.168.120.161:3007/business_a?serverTimezone=UTC&useSSL=false
    username: root
    password: 123456
    connectionTimeoutMilliseconds: 30000
    idleTimeoutMilliseconds: 60000
    maxLifetimeMilliseconds: 1800000
    maxPoolSize: 50
    minPoolSize: 1

rules:
- !READWRITE_SPLITTING
  dataSources:
    readwrite_ds:
      type: Static
      props:
        write-data-source-name: write_ds
        read-data-source-names: read_ds_0,read_ds_1
- !SHARDING
  tables:
    table_b:
      actualDataNodes: write_ds.table_b0,write_ds.table_b1
      tableStrategy:
        standard:
          shardingColumn: id
          shardingAlgorithmName: id_hash_mode
  shardingAlgorithms:
    id_hash_mode:
      type: HASH_MOD
      props:
        sharding-count: 2
```

重启容器，发现多出了table_b逻辑表：

```sql
mysql> show tables;
+----------------------------------+------------+
| Tables_in_readwrite_splitting_db | Table_type |
+----------------------------------+------------+
| table_a                          | BASE TABLE |
| table_b                          | BASE TABLE |
+----------------------------------+------------+
2 rows in set (0.02 sec)
```

插入数据，查看物理语句：

```
### ShardingProxy查询
mysql> select * from table_b;
+----+--------+----------+
| id | b_name | b_status |
+----+--------+----------+
|  0 | 0      |        0 |
|  2 | 2      |        2 |
|  4 | 4      |        4 |
|  1 | 1      |        1 |
|  3 | 3      |        3 |
+----+--------+----------+
5 rows in set (0.07 sec)

### ShardingProxy日志
[INFO ] 2023-05-09 11:58:56.226 [ShardingSphere-Command-1] ShardingSphere-SQL - Logic SQL: select * from table_b
[INFO ] 2023-05-09 11:58:56.227 [ShardingSphere-Command-1] ShardingSphere-SQL - SQLStatement: MySQLSelectStatement(table=Optional.empty, limit=Optional.empty, lock=Optional.empty, window=Optional.empty)
[INFO ] 2023-05-09 11:58:56.227 [ShardingSphere-Command-1] ShardingSphere-SQL - Actual SQL: write_ds ::: select * from table_b0 UNION ALL select * from table_b1





### ShardingProxy插入
mysql> INSERT INTO table_b VALUES(5,5,5);
Query OK, 1 row affected (0.21 sec)

### ShardingProxy日志
[INFO ] 2023-05-09 12:01:21.402 [ShardingSphere-Command-2] ShardingSphere-SQL - Logic SQL: INSERT INTO table_b VALUES(5,5,5)
[INFO ] 2023-05-09 12:01:21.403 [ShardingSphere-Command-2] ShardingSphere-SQL - SQLStatement: MySQLInsertStatement(setAssignment=Optional.empty, onDuplicateKeyColumns=Optional.empty)
[INFO ] 2023-05-09 12:01:21.403 [ShardingSphere-Command-2] ShardingSphere-SQL - Actual SQL: write_ds ::: INSERT INTO table_b1 VALUES(5, 5, 5)




### ShardingProxy插入
mysql> INSERT INTO table_b VALUES(6,6,6);
Query OK, 1 row affected (0.04 sec)

### ShardingProxy日志
[INFO ] 2023-05-09 12:03:00.822 [ShardingSphere-Command-3] ShardingSphere-SQL - Logic SQL: INSERT INTO table_b VALUES(6,6,6)
[INFO ] 2023-05-09 12:03:00.823 [ShardingSphere-Command-3] ShardingSphere-SQL - SQLStatement: MySQLInsertStatement(setAssignment=Optional.empty, onDuplicateKeyColumns=Optional.empty)
[INFO ] 2023-05-09 12:03:00.823 [ShardingSphere-Command-3] ShardingSphere-SQL - Actual SQL: write_ds ::: INSERT INTO table_b0 VALUES(6, 6, 6)
```

