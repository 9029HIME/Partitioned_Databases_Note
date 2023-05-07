package com.genn.A;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.genn.A.mapper.TableAMapper;
import com.genn.A.pojo.TableA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class ReadWriteSplitTest extends com.genn.A.BaseTest {

    @Autowired
    private TableAMapper tableAMapper;

    @Test
    public void readWriteSplit(){
        // slave01 and slave02
        List<TableA> tableAS = tableAMapper.selectList(new QueryWrapper<TableA>());
        tableAS = tableAMapper.selectList(new QueryWrapper<TableA>());

        // master
        TableA a = new TableA();
        a.setaName("insert");
        a.setaStatus(3);
        tableAMapper.insert(a);
    }

}
