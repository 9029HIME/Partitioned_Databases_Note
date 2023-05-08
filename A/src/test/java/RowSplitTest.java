package com.genn.A;

import com.genn.A.mapper.TableAMapper;
import com.genn.A.mapper.TableBMapper;
import com.genn.A.pojo.TableB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class RowSplitTest extends com.genn.A.BaseTest {

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

}
