package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    // 根据博客id查询详情
    @Override
    public Result queryBlogById(Long id) {
        // 1、查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2、查询blog有关的用户
        queryBlogUser(blog);

        // 3、查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    // 保存用推的方式
    @Override
    public Result saveBlog(Blog blog) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 保存 blog
        blog.setUserId(userId);
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("发布失败！");
        }
        // 查询用户的粉丝
        List<Follow> fans = followService.query().eq("follow_user_id", userId).list();
        // 将blog推给粉丝
        fans.forEach(fan -> {
            // 获取粉丝id
            Long fanId = fan.getUserId();
            // 推送
            stringRedisTemplate.opsForZSet().add(FEED_KEY + fanId, blog.getId().toString(), System.currentTimeMillis());
        });
        return Result.ok(blog.getId());
    }

    // 点赞排名
    // zrange 0 - 4 得到用户id和分数 然后根据id查询用户返回
    @Override
    public Result queryBlogLikes(Long id) {
        // 1、查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().reverseRange(BLOG_LIKED_KEY + id, 0, 4);
        // 2、查询用户
        if (top5 == null){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3、根据id查询用户
        // 注意这里查询会出现顺序异常
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query().in("id",ids).last("ORDER BY FIELD(id," + idStr +  ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4、返回
        return Result.ok(users);
    }

    // 查询用户所关注的用户所发布的博客
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1、获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2、查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 3、解析收件箱：blogId，minTime，offset
        // 收集id
        List< Long> ids = new ArrayList<>(typedTuples.size());
        Long minTime = 0L;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples){
            // 获取id
            String blogId = tuple.getValue();
            ids.add(Long.valueOf(blogId));
            // 获取时间戳(最小）
            Long time = tuple.getScore().longValue();
            if (time == minTime){
                os++;
            } else {
                minTime = time;
                os = 1;
            }
            // 获取一样值的个数
        }
        // 4、根据id查询blog(注意需要有序)
        //  mybatis
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idstr + ")").list();
        blogs.forEach(blog -> {
            // 5、查询blog有关的用户
            queryBlogUser(blog);
            // 6、查询blog是否被点赞
            isBlogLiked(blog);
        });
        // 5、封装返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void isBlogLiked(Blog blog) {
        // 1、判断当前用户是否点赞
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        Long id = blog.getId();
        // 判断
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        blog.setIsLike(score != null);
    }

    // 查询最热的多个博客
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
                this.isBlogLiked(blog);
                this.queryBlogUser(blog);
        });
        return Result.ok(records);
    }

    // 点赞 存入redis
    @Override
    public Result likeBlog(Long id) {
        // 1、判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();

        // 判断
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        if (score == null) {
            // 2、未点赞则可以点赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // +1 并将用户存入redis
            // 为什么分数用时间戳：时间戳作为 score 可以按点赞时间先后排序 最新点赞的用户排在前
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3、已点赞则取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
