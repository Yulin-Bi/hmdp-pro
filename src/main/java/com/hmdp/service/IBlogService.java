package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    // 根据博客id查询详情
    Result queryBlogById(Long id);

    // 查询博客列表
    Result queryHotBlog(Integer current);

    // 点赞博客
    Result likeBlog(Long id);

    // 查询博客点赞列表
    Result queryBlogLikes(Long id);

    // 保存博客
    Result saveBlog(Blog blog);

    // 把博客推送给粉丝
    Result queryBlogOfFollow(Long max, Integer offset);
}
