package com.im.live.spi;

import com.im.common.api.ResultCode;
import com.im.common.domain.AdminWorkCategoryDTO;
import com.im.common.enums.WorkBoardType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminWorkCategorySpi;
import com.im.live.entity.LiveVideoCategory;
import com.im.live.service.LiveVideoCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台板块分类字典契约的实现（{@link AdminWorkCategorySpi}）。
 *
 * <p>本类几乎是薄壳：真正的校验（同板块内同名唯一、名称长度、排序起点、跨板块越权拦截）都在
 * {@link LiveVideoCategoryService} 里，因为用户端读分类、视频后台改分类、通用后台改分类三条路径
 * 必须走同一套规则。在这里再写一遍，就是四板块改两板块的开始。
 *
 * <p>唯一新增的职责是把实体转成 {@link AdminWorkCategoryDTO}，并把调用方声明的 {@code type}
 * 原样传给服务层做跨板块比对——<b>本类不做「type 不匹配就退回 video」这种兜底</b>，
 * 静默兜底会把越权尝试变成一次成功的操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkCategoryAdminSpiImpl implements AdminWorkCategorySpi {

    private final LiveVideoCategoryService categoryService;

    @Override
    public List<AdminWorkCategoryDTO> listCategories(String type) {
        List<LiveVideoCategory> rows = categoryService.listAll(requireBoard(type));
        List<AdminWorkCategoryDTO> dtos = new ArrayList<>(rows.size());
        for (LiveVideoCategory row : rows) {
            dtos.add(toDTO(row));
        }
        return dtos;
    }

    @Override
    public AdminWorkCategoryDTO createCategory(String type, String name, Integer sort, Long operatorId) {
        String board = requireBoard(type);
        LiveVideoCategory created = categoryService.create(board, name, sort, operatorId);
        log.info("[后台分类管理] operator={} 在{}板块新增分类: id={}, name={}",
                operatorId, board, created.getId(), created.getName());
        return toDTO(created);
    }

    @Override
    public AdminWorkCategoryDTO updateCategory(String type, Long id, String name, Integer sort,
                                               Integer status, Long operatorId) {
        String board = requireBoard(type);
        LiveVideoCategory updated = categoryService.update(board, id, name, sort, status, operatorId);
        log.info("[后台分类管理] operator={} 修改{}板块分类 {}: name={}, sort={}, status={}",
                operatorId, board, id, updated.getName(), updated.getSort(), updated.getStatus());
        return toDTO(updated);
    }

    @Override
    public void deleteCategory(String type, Long id, Long operatorId) {
        String board = requireBoard(type);
        categoryService.delete(board, id, operatorId);
        log.info("[后台分类管理] operator={} 删除{}板块分类 {}", operatorId, board, id);
    }

    /**
     * 板块串校验：四个板块的分类都从这里走（含 video），未知值直接拒绝。
     *
     * <p>返回的是规范化后的 code（trim + 小写口径由 {@link WorkBoardType#of} 负责），
     * 服务层拿它跟行上的 type 比对，所以这里不能把原始入参透传下去。
     */
    private String requireBoard(String type) {
        WorkBoardType board = WorkBoardType.of(type);
        if (board == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知的板块：" + type);
        }
        return board.getCode();
    }

    private AdminWorkCategoryDTO toDTO(LiveVideoCategory row) {
        return AdminWorkCategoryDTO.builder()
                .id(row.getId())
                .type(row.getType())
                .name(row.getName())
                .sort(row.getSort())
                .status(row.getStatus())
                .build();
    }
}
