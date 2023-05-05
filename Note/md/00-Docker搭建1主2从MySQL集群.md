# 前提

MySQL的主从架构、主从同步原理不再赘述了，以下是主从相关信息：

|                   从机1                    |                   主机                    |                   从机2                    |
| :----------------------------------------: | :---------------------------------------: | :----------------------------------------: |
|               name = slave01               |               name = master               |               name = slave02               |
|                port = 3005                 |                port = 3006                |                port = 3007                 |
| /root/mysql/slave01/conf:/etc/mysql/conf.d | /root/mysql/master/conf:/etc/mysql/conf.d | /root/mysql/slave02/conf:/etc/mysql/conf.d |
|  /root/mysql/slave01/data:/var/lib/mysql   |  /root/mysql/master/data:/var/lib/mysql   |  /root/mysql/slave02/data:/var/lib/mysql   |
|             password = 123456              |             password = 123456             |             password = 123456              |

保证docker内有mysql8镜像：

```bash
root@kjg-PC:~# docker logout harbor.genn.com
Removing login credentials for harbor.genn.com
root@kjg-PC:~# docker pull mysql:8.0.29
8.0.29: Pulling from library/mysql
e54b73e95ef3: Pull complete 
327840d38cb2: Pull complete 
642077275f5f: Pull complete 
e077469d560d: Pull complete 
cbf214d981a6: Pull complete 
7d1cc1ea1b3d: Pull complete 
d48f3c15cb80: Pull complete 
94c3d7b2c9ae: Pull complete 
f6cfbf240ed7: Pull complete 
e12b159b2a12: Pull complete 
4e93c6fd777f: Pull complete 
Digest: sha256:152cf187a3efc56afb0b3877b4d21e231d1d6eb828ca9221056590b0ac834c75
Status: Downloaded newer image for mysql:8.0.29
root@kjg-PC:~# docker images | grep mysql
mysql                                                                      8.0.29              33037edcac9b        9 months ago        444MB
```

# 搭建主

在/root/mysql/master/conf下准备主机的配置文件my.cnf：

```properties
[mysqld]
port=3006
# 在集群中的唯一id，[1,231]
server-id=1
# binlog数据格式
binlog_format=STATEMENT
# binlog文件名
#log-bin=binlog
# 需要主从复制的库（不指定默认全部）
#binlog-do-db=business_demo
```

启动主机**（这里有个坑，不能使用-h localhost，否则只会连3306）**：

```bash
root@kjg-PC:~# docker run -d \
> -p 3006:3006 \
> -v /root/mysql/master/conf:/etc/mysql/conf.d \
> -v /root/mysql/master/data:/var/lib/mysql \
> -e MYSQL_ROOT_PASSWORD=123456 \
> -e LANG=C.UTF-8 \
> --name master \
> mysql:8.0.29
81524816d77e154e8b6d1648d842ad4b63ece5a03f28a4f0f425a650888b50a4

root@kjg-PC:~# mysql -uroot -p123456 -h192.168.120.161 -P 3006
mysql: [Warning] Using a password on the command line interface can be insecure.
Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 8
Server version: 8.0.29 MySQL Community Server - GPL

Copyright (c) 2000, 2022, Oracle and/or its affiliates.

Oracle is a registered trademark of Oracle Corporation and/or its
affiliates. Other names may be trademarks of their respective
owners.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| information_schema |
| mysql              |
| performance_schema |
| sys                |
+--------------------+
4 rows in set (0.00 sec)
```

为从机创建访问账号、设置密码、赋予权限、刷新权限（注意是数字1不是字母l）：

```sql
mysql> CREATE USER 's1ave'@'%';
Query OK, 0 rows affected (0.04 sec)

mysql> ALTER USER 's1ave'@'%' IDENTIFIED WITH mysql_native_password BY '123456';
Query OK, 0 rows affected (0.01 sec)

mysql> GRANT REPLICATION SLAVE ON *.* TO 's1ave'@'%';
Query OK, 0 rows affected (0.00 sec)

mysql> FLUSH PRIVILEGES;
Query OK, 0 rows affected (0.01 sec)
```

同时看一看master的状态，可以看到目前正在写000003的binlog，最新偏移量是1043

```sql
mysql> SHOW MASTER STATUS;
+---------------+----------+--------------+------------------+-------------------+
| File          | Position | Binlog_Do_DB | Binlog_Ignore_DB | Executed_Gtid_Set |
+---------------+----------+--------------+------------------+-------------------+
| binlog.000003 |     1043 |              |                  |                   |
+---------------+----------+--------------+------------------+-------------------+
1 row in set (0.00 sec)
```

# 搭建从01

和主类似，不再赘述，直接贴上过程：

```properties
[mysqld]
port=3005
# 在集群中的唯一id
server-id=2
# relaylog文件名
relay-log=relay-bin
```

```bash
root@kjg-PC:~# docker run -d \
> -p 3005:3005 \
> -v /root/mysql/slave01/conf:/etc/mysql/conf.d \
> -v /root/mysql/slave01/data:/var/lib/mysql \
> -e MYSQL_ROOT_PASSWORD=123456 \
> -e LANG=C.UTF-8 \
> --name slave01 \
> mysql:8.0.29
3199c9027a31d1c1c0dde69be642155c78fe4e46c73ef31d5684f007614ca249


root@kjg-PC:~# mysql -u root -p123456 -h 192.168.120.161 -P 3005
mysql: [Warning] Using a password on the command line interface can be insecure.
Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 9
Server version: 8.0.29 MySQL Community Server - GPL

Copyright (c) 2000, 2022, Oracle and/or its affiliates.

Oracle is a registered trademark of Oracle Corporation and/or its
affiliates. Other names may be trademarks of their respective
owners.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| information_schema |
| mysql              |
| performance_schema |
| sys                |
+--------------------+
4 rows in set (0.01 sec)
```

```sql
mysql> CHANGE MASTER TO MASTER_HOST='192.168.120.161',MASTER_USER='s1ave',MASTER_PASSWORD='123456',MASTER_PORT=3006,MASTER_LOG_FILE='binlog.000003',MASTER_LOG_POS=1043;
Query OK, 0 rows affected, 8 warnings (0.03 sec)

mysql> START SLAVE;
Query OK, 0 rows affected, 1 warning (0.02 sec)

mysql> SHOW SLAVE STATUS\G
*************************** 1. row ***************************
               Slave_IO_State: Waiting for source to send event
                  Master_Host: 192.168.120.161
                  Master_User: s1ave
                  Master_Port: 3006
                Connect_Retry: 60
              Master_Log_File: binlog.000003
          Read_Master_Log_Pos: 1043
               Relay_Log_File: relay-bin.000002
                Relay_Log_Pos: 323
        Relay_Master_Log_File: binlog.000003
             Slave_IO_Running: Yes
            Slave_SQL_Running: Yes
              Replicate_Do_DB: 
          Replicate_Ignore_DB: 
           Replicate_Do_Table: 
       Replicate_Ignore_Table: 
      Replicate_Wild_Do_Table: 
  Replicate_Wild_Ignore_Table: 
                   Last_Errno: 0
                   Last_Error: 
                 Skip_Counter: 0
          Exec_Master_Log_Pos: 1043
              Relay_Log_Space: 527
              Until_Condition: None
               Until_Log_File: 
                Until_Log_Pos: 0
           Master_SSL_Allowed: No
           Master_SSL_CA_File: 
           Master_SSL_CA_Path: 
              Master_SSL_Cert: 
            Master_SSL_Cipher: 
               Master_SSL_Key: 
        Seconds_Behind_Master: 0
Master_SSL_Verify_Server_Cert: No
                Last_IO_Errno: 0
                Last_IO_Error: 
               Last_SQL_Errno: 0
               Last_SQL_Error: 
  Replicate_Ignore_Server_Ids: 
             Master_Server_Id: 1
                  Master_UUID: a77c741b-eb3a-11ed-a0e8-0242ac110002
             Master_Info_File: mysql.slave_master_info
                    SQL_Delay: 0
          SQL_Remaining_Delay: NULL
      Slave_SQL_Running_State: Replica has read all relay log; waiting for more updates
           Master_Retry_Count: 86400
                  Master_Bind: 
      Last_IO_Error_Timestamp: 
     Last_SQL_Error_Timestamp: 
               Master_SSL_Crl: 
           Master_SSL_Crlpath: 
           Retrieved_Gtid_Set: 
            Executed_Gtid_Set: 
                Auto_Position: 0
         Replicate_Rewrite_DB: 
                 Channel_Name: 
           Master_TLS_Version: 
       Master_public_key_path: 
        Get_master_public_key: 0
            Network_Namespace: 
1 row in set, 1 warning (0.00 sec)
```

# 搭建从02

同样的，和从01一样：

```properties
[mysqld]
port=3007
# 在集群中的唯一id
server-id=3
# relaylog文件名
relay-log=relay-bin
```

```sql
root@kjg-PC:~# docker run -d \
> -p 3007:3007 \
> -v /root/mysql/slave02/conf:/etc/mysql/conf.d \
> -v /root/mysql/slave02/data:/var/lib/mysql \
> -e MYSQL_ROOT_PASSWORD=123456 \
> -e LANG=C.UTF-8 \
> --name slave02 \
> mysql:8.0.29
109467f8700752eb340b4c8ba2abc650d99047f0c7666f56000d2bd6558aa035


root@kjg-PC:~# mysql -u root -p123456 -h192.168.120.161 -P 3007
mysql: [Warning] Using a password on the command line interface can be insecure.
Welcome to the MySQL monitor.  Commands end with ; or \g.
Your MySQL connection id is 8
Server version: 8.0.29 MySQL Community Server - GPL

Copyright (c) 2000, 2022, Oracle and/or its affiliates.

Oracle is a registered trademark of Oracle Corporation and/or its
affiliates. Other names may be trademarks of their respective
owners.

Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.

mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| information_schema |
| mysql              |
| performance_schema |
| sys                |
+--------------------+
4 rows in set (0.00 sec)
```

```sql
mysql> CHANGE MASTER TO MASTER_HOST='192.168.120.161',MASTER_USER='s1ave',MASTER_PASSWORD='123456',MASTER_PORT=3006,MASTER_LOG_FILE='binlog.000003',MASTER_LOG_POS=1043;
Query OK, 0 rows affected, 8 warnings (0.08 sec)

mysql> START SLAVE;
Query OK, 0 rows affected, 1 warning (0.02 sec)

mysql> SHOW SLAVE STATUS\G
*************************** 1. row ***************************
               Slave_IO_State: Waiting for source to send event
                  Master_Host: 192.168.120.161
                  Master_User: s1ave
                  Master_Port: 3006
                Connect_Retry: 60
              Master_Log_File: binlog.000003
          Read_Master_Log_Pos: 1043
               Relay_Log_File: relay-bin.000002
                Relay_Log_Pos: 323
        Relay_Master_Log_File: binlog.000003
             Slave_IO_Running: Yes
            Slave_SQL_Running: Yes
              Replicate_Do_DB: 
          Replicate_Ignore_DB: 
           Replicate_Do_Table: 
       Replicate_Ignore_Table: 
      Replicate_Wild_Do_Table: 
  Replicate_Wild_Ignore_Table: 
                   Last_Errno: 0
                   Last_Error: 
                 Skip_Counter: 0
          Exec_Master_Log_Pos: 1043
              Relay_Log_Space: 527
              Until_Condition: None
               Until_Log_File: 
                Until_Log_Pos: 0
           Master_SSL_Allowed: No
           Master_SSL_CA_File: 
           Master_SSL_CA_Path: 
              Master_SSL_Cert: 
            Master_SSL_Cipher: 
               Master_SSL_Key: 
        Seconds_Behind_Master: 0
Master_SSL_Verify_Server_Cert: No
                Last_IO_Errno: 0
                Last_IO_Error: 
               Last_SQL_Errno: 0
               Last_SQL_Error: 
  Replicate_Ignore_Server_Ids: 
             Master_Server_Id: 1
                  Master_UUID: a77c741b-eb3a-11ed-a0e8-0242ac110002
             Master_Info_File: mysql.slave_master_info
                    SQL_Delay: 0
          SQL_Remaining_Delay: NULL
      Slave_SQL_Running_State: Replica has read all relay log; waiting for more updates
           Master_Retry_Count: 86400
                  Master_Bind: 
      Last_IO_Error_Timestamp: 
     Last_SQL_Error_Timestamp: 
               Master_SSL_Crl: 
           Master_SSL_Crlpath: 
           Retrieved_Gtid_Set: 
            Executed_Gtid_Set: 
                Auto_Position: 0
         Replicate_Rewrite_DB: 
                 Channel_Name: 
           Master_TLS_Version: 
       Master_public_key_path: 
        Get_master_public_key: 0
            Network_Namespace: 
1 row in set, 1 warning (0.01 sec)
```

# 理想情况

两台从机的SHOW SLAVE STATUS均展示`Slave_IO_Running: Yes`和`Slave_SQL_Running: Yes`，代表主从同步建立完成，同时可以发现Docker也有对应容器信息：

```bash
root@kjg-PC:~# docker ps -a | grep mysql
109467f87007        mysql:8.0.29                                        "docker-entrypoint.s…"   3 minutes ago       Up 3 minutes                 3306/tcp, 0.0.0.0:3007->3007/tcp, 33060/tcp   slave02
3199c9027a31        mysql:8.0.29                                        "docker-entrypoint.s…"   12 minutes ago      Up 12 minutes                3306/tcp, 0.0.0.0:3005->3005/tcp, 33060/tcp   slave01
81524816d77e        mysql:8.0.29                                        "docker-entrypoint.s…"   39 minutes ago      Up 39 minutes                3306/tcp, 0.0.0.0:3006->3006/tcp, 33060/tcp   master
```

# 测试主从同步

准备SQL：

```sql
CREATE DATABASE business_a;
USE business_a;
CREATE TABLE table_a (
    id BIGINT AUTO_INCREMENT,
    a_name VARCHAR(30),
    a_status INT,
    PRIMARY KEY (id)
);
INSERT INTO table_a(a_name,a_status) VALUES ('hello1',1);
INSERT INTO table_a(a_name,a_status) VALUES ('hello2',1);
```

在Master执行：

```mysql
mysql> show global variables like '%port%';
+--------------------------+-------+
| Variable_name            | Value |
+--------------------------+-------+
| admin_port               | 33062 |
| large_files_support      | ON    |
| mysqlx_port              | 33060 |
| mysqlx_port_open_timeout | 0     |
| port                     | 3006  |
| report_host              |       |
| report_password          |       |
| report_port              | 3006  |
| report_user              |       |
| require_secure_transport | OFF   |
+--------------------------+-------+
10 rows in set (0.02 sec)

mysql> CREATE DATABASE business_a;
Query OK, 1 row affected (0.03 sec)

mysql> USE business_a;
Database changed
mysql> CREATE TABLE table_a (
    ->     id BIGINT AUTO_INCREMENT,
    ->     a_name VARCHAR(30),
    ->     a_status INT,
    ->     PRIMARY KEY (id)
    -> );
Query OK, 0 rows affected (0.02 sec)

mysql> INSERT INTO table_a(a_name,a_status) VALUES ('hello1',1);
Query OK, 1 row affected (0.01 sec)

mysql> INSERT INTO table_a(a_name,a_status) VALUES ('hello2',1);
Query OK, 1 row affected (0.01 sec)
```

查看从01和从02：

```sql
mysql> show global variables like '%port%';
+--------------------------+-------+
| Variable_name            | Value |
+--------------------------+-------+
| admin_port               | 33062 |
| large_files_support      | ON    |
| mysqlx_port              | 33060 |
| mysqlx_port_open_timeout | 0     |
| port                     | 3005  |
| report_host              |       |
| report_password          |       |
| report_port              | 3005  |
| report_user              |       |
| require_secure_transport | OFF   |
+--------------------------+-------+
10 rows in set (0.02 sec)

mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| business_a         |
| information_schema |
| mysql              |
| performance_schema |
| sys                |
+--------------------+
5 rows in set (0.00 sec)

mysql> use business_a;
Reading table information for completion of table and column names
You can turn off this feature to get a quicker startup with -A

Database changed
mysql> select * from table_a;
+----+--------+----------+
| id | a_name | a_status |
+----+--------+----------+
|  1 | hello1 |        1 |
|  2 | hello2 |        1 |
+----+--------+----------+
2 rows in set (0.00 sec)
```

```sql
mysql> show global variables like '%port%';
+--------------------------+-------+
| Variable_name            | Value |
+--------------------------+-------+
| admin_port               | 33062 |
| large_files_support      | ON    |
| mysqlx_port              | 33060 |
| mysqlx_port_open_timeout | 0     |
| port                     | 3007  |
| report_host              |       |
| report_password          |       |
| report_port              | 3007  |
| report_user              |       |
| require_secure_transport | OFF   |
+--------------------------+-------+
10 rows in set (0.00 sec)

mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| business_a         |
| information_schema |
| mysql              |
| performance_schema |
| sys                |
+--------------------+
5 rows in set (0.01 sec)

mysql> use business_a;
Reading table information for completion of table and column names
You can turn off this feature to get a quicker startup with -A

Database changed
mysql> select * from table_a;
+----+--------+----------+
| id | a_name | a_status |
+----+--------+----------+
|  1 | hello1 |        1 |
|  2 | hello2 |        1 |
+----+--------+----------+
2 rows in set (0.00 sec)
```

可以发现，主从同步没问题。