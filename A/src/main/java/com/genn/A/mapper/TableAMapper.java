package com.genn.A.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genn.A.pojo.TableA;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Repository
@Mapper
public interface TableAMapper extends BaseMapper<TableA> {
}
