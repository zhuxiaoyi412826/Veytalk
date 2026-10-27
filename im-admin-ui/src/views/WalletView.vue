<template>
  <div class="wallet">
    <div class="toolbar">
      <h2 class="page-title">钱包与礼物</h2>
      <span class="grow" />
      <span v-if="overview" class="muted">数据截至 {{ formatDateTime(overview.generatedAt) }}</span>
      <el-button :loading="overviewLoading" @click="loadOverview">
        <el-icon><Refresh /></el-icon>
        <span>刷新数字</span>
      </el-button>
    </div>

    <el-row :gutter="16" class="overview">
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value">
              <el-tag :type="overview?.walletEnabled === false ? 'info' : 'success'" effect="plain" size="large">
                {{ overview?.walletEnabled === false ? '已关闭' : '已开启' }}
              </el-tag>
            </span>
            <span class="muted">
              钱包总开关
              <el-tooltip placement="top" content="运行态取自 im.wallet.enabled；关掉后充值、送礼、转账入口全部停摆，本页只读数字">
                <el-icon class="hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ displayValue(overview?.accountCount) }}</span>
            <span class="muted">开户数（有钱包行的用户）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.totalBalance) }}</span>
            <span class="muted">全站当前余额合计</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.totalRecharged) }}</span>
            <span class="muted">累计充值（type=recharge 入账之和）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.totalConsumed) }}</span>
            <span class="muted">累计消费</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num">{{ formatNumber(overview?.todayTxCount) }}</span>
            <span class="muted">今日流水笔数</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num" :class="netClass(overview?.todayTxAmount)">
              {{ signedAmount(overview?.todayTxAmount) }}
            </span>
            <span class="muted">今日流水净额（入 - 出）</span>
          </div>
        </el-card>
      </el-col>
      <el-col :lg="6" :md="12" :sm="12" :xs="24">
        <el-card class="mb" shadow="never">
          <div class="kpi">
            <span class="kpi-value num warning">{{ displayValue(overview?.todayAdjustCount) }}</span>
            <span class="muted">
              今日后台调账
              <el-tooltip placement="top" content="这几笔不是用户自己动钱，是我们动的。它就是这一屏的对自身问责指标">
                <el-icon class="hint-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      class="hint"
      :closable="false"
      type="warning"
      title="「调整余额」是整个后台唯一能凭空造出货币的按钮，扣减也不允许把余额调成负数"
      description="调账走的是用户侧同一条 credit/debit：条件 UPDATE 加同事务补一条 balance_after 流水，事由必填、操作人进备注，所以每一笔都查得到是谁在什么时候为什么改的。礼物目录只改上下架——不改价、不改名、不删行：im_gift_record 冗余了礼物名与成交单价，改价会让「今天卖 100、明天卖 10」变成两个人各执一词。送礼流水是账本，一个写动作都没有。"
    />

    <el-tabs v-model="tab" class="mb" @tab-change="onTabChange">
      <el-tab-pane label="钱包台账" name="wallet">
        <div class="filters">
          <el-input
            v-model="walletQuery.keyword"
            :maxlength="30"
            clearable
            class="keyword"
            placeholder="昵称关键词 / 用户 ID"
            @clear="reloadWallets"
            @keyup.enter="reloadWallets"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-input
            v-model="walletQuery.userId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="用户 ID 精确"
            @clear="reloadWallets"
            @keyup.enter="reloadWallets"
          />
          <el-input
            v-model="walletQuery.minBalance"
            :maxlength="12"
            clearable
            class="uid"
            placeholder="余额下限"
            @clear="reloadWallets"
            @keyup.enter="reloadWallets"
          />
          <el-button :loading="walletLoading" @click="reloadWallets">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="walletLoading" :data="wallets">
            <el-table-column label="用户" min-width="180">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.nickname || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column align="right" label="余额" width="120">
              <template #default="{ row }">
                <span class="num strong">{{ formatNumber(row.balance) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="累计充值" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.totalRecharged) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="累计消费" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.totalConsumed) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近签到" width="110">
              <template #default="{ row }">
                <span class="num">{{ row.signLastDate || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="变动时间" width="130">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.updateTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="150">
              <template #default="{ row }">
                <el-button text type="primary" @click="openWallet(row.userId)">档案</el-button>
                <el-button text type="warning" @click="openAdjust(row)">调账</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的钱包" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="walletQuery.current"
              v-model:page-size="walletQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="walletTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadWallets"
              @current-change="loadWallets"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="账本流水" name="tx">
        <div class="filters">
          <el-input
            v-model="txQuery.userId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="用户 ID（留空=全站）"
            @clear="reloadTxs"
            @keyup.enter="reloadTxs"
          />
          <el-select v-model="txQuery.type" class="status" clearable placeholder="全部类型" @change="reloadTxs">
            <el-option v-for="item in TX_TYPES" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <el-input
            v-model="txQuery.keyword"
            :maxlength="30"
            clearable
            class="keyword"
            placeholder="备注关键词"
            @clear="reloadTxs"
            @keyup.enter="reloadTxs"
          />
          <el-date-picker
            v-model="txRange"
            :default-time="defaultTime"
            class="range"
            end-placeholder="结束"
            start-placeholder="开始"
            type="datetimerange"
            unlink-panels
            value-format="YYYY-MM-DDTHH:mm:ss"
            @change="reloadTxs"
          />
          <el-checkbox v-model="txQuery.minusOnly" @change="reloadTxs">只看扣减</el-checkbox>
          <el-button :loading="txLoading" @click="reloadTxs">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="info"
          title="不带用户 ID 就是全站检索，im_wallet_transaction 只有 idx_user_time 一条索引"
          description="所以全站口径下单页被服务端夹到 50 条（带用户 ID 才放到 100）。想查一个人的完整账本，把用户 ID 填上，或者直接在「钱包台账」那一行点档案——那里带最近 10 条。"
        />

        <el-card shadow="never">
          <el-table v-loading="txLoading" :data="txs">
            <el-table-column label="时间" width="130">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="用户" min-width="150">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.nickname || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag :type="txTypeTag(row.type)" effect="plain" size="small">{{ txTypeText(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column align="right" label="变动额" width="110">
              <template #default="{ row }">
                <span class="num strong" :class="netClass(row.amount)">{{ signedAmount(row.amount) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="变动后余额" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.balanceAfter) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="关联" width="130">
              <template #default="{ row }">
                <span class="muted code">{{ refText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.remark || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的流水" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="txQuery.current"
              v-model:page-size="txQuery.size"
              :page-sizes="[10, 20, 50]"
              :total="txTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadTxs"
              @current-change="loadTxs"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="送礼流水" name="giftRecord">
        <div class="filters">
          <el-input
            v-model="giftRecordQuery.roomId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="房间 ID"
            @clear="reloadGiftRecords"
            @keyup.enter="reloadGiftRecords"
          />
          <el-input
            v-model="giftRecordQuery.senderId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="送礼人 ID"
            @clear="reloadGiftRecords"
            @keyup.enter="reloadGiftRecords"
          />
          <el-input
            v-model="giftRecordQuery.receiverId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="收礼人 ID"
            @clear="reloadGiftRecords"
            @keyup.enter="reloadGiftRecords"
          />
          <el-date-picker
            v-model="giftRecordRange"
            :default-time="defaultTime"
            class="range"
            end-placeholder="结束"
            start-placeholder="开始"
            type="datetimerange"
            unlink-panels
            value-format="YYYY-MM-DDTHH:mm:ss"
            @change="reloadGiftRecords"
          />
          <el-button :loading="giftRecordLoading" @click="reloadGiftRecords">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="info"
          title="这一屏是只读账本：没有「撤销这笔礼」，也没有「退回积分」"
          description="送礼是即时消耗，逆向退款等于篡改他人之间的交易记录。每行的礼物名与单价是下单那一刻的快照，所以礼物后来改名、下架乃至删行都不影响这里读出来的历史。im_gift_record 只有 idx_room_time / idx_receiver_time 两条索引，单页夹 50，建议带上房间或时间窗。"
        />

        <el-card shadow="never">
          <el-table v-loading="giftRecordLoading" :data="giftRecords">
            <el-table-column label="时间" width="130">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="送礼人" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.senderName || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.senderId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="收礼人" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.receiverName || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.receiverId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="礼物" min-width="130">
              <template #default="{ row }">
                <span class="gift-icon">{{ row.giftIcon || '' }}</span>
                <span>{{ row.giftName || EMPTY_TEXT }}</span>
                <span class="muted">#{{ row.giftId }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="数量" width="80">
              <template #default="{ row }">
                <span class="num">×{{ formatNumber(row.count) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="单价" width="90">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.unitPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="总价" width="110">
              <template #default="{ row }">
                <span class="num strong">{{ formatNumber(row.totalPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="房间" min-width="160">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.roomTitle || '（房间已清理）' }}</span>
                  <span class="muted">#{{ row.roomId }}</span>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的送礼记录" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="giftRecordQuery.current"
              v-model:page-size="giftRecordQuery.size"
              :page-sizes="[10, 20, 50]"
              :total="giftRecordTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadGiftRecords"
              @current-change="loadGiftRecords"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="礼物目录" name="gift">
        <div class="filters">
          <span class="muted">全量 {{ gifts.length }} 个（含已下架），按 sort 升序；目录不长，所以这里不分页</span>
          <span class="grow" />
          <el-button :loading="giftLoading" @click="loadGifts">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="giftLoading" :data="gifts">
            <el-table-column label="排序" width="70">
              <template #default="{ row }">
                <span class="num">{{ displayValue(row.sort) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="图标" width="70">
              <template #default="{ row }">
                <span class="gift-icon">{{ row.icon || '' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.name }}</span>
                  <span class="muted">#{{ row.giftId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column align="right" label="单价" width="100">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.price) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="row.kind === 'effect' ? 'warning' : 'info'" effect="plain" size="small">
                  {{ kindText(row.kind) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ row.status === 1 ? '已上架' : '已下架' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="130">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.updateTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button v-if="row.status === 1" text type="warning" @click="toggleGift(row, false)">下架</el-button>
                <el-button v-else text type="success" @click="toggleGift(row, true)">上架</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="礼物目录是空的" :image-size="80" />
            </template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 单人钱包档案：详情接口回同一个 AdminWalletDTO，顺带最近 10 条流水 -->
    <el-dialog v-model="detail.visible" :title="`钱包档案：#${detail.userId}`" width="720px">
      <div v-loading="detail.loading">
        <el-empty
          v-if="detail.loaded && !detail.wallet"
          description="该用户从未开过钱包行（读一次不会顺手建行）"
          :image-size="80"
        />
        <template v-else-if="detail.wallet">
          <el-descriptions :column="2" border class="block">
            <el-descriptions-item label="用户">
              {{ detail.wallet.nickname || DELETED_NAME }} #{{ detail.wallet.userId }}
            </el-descriptions-item>
            <el-descriptions-item label="余额">
              <span class="num strong">{{ formatNumber(detail.wallet.balance) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="累计充值">
              <span class="num">{{ formatNumber(detail.wallet.totalRecharged) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="累计消费">
              <span class="num">{{ formatNumber(detail.wallet.totalConsumed) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="最近签到">{{ detail.wallet.signLastDate || EMPTY_TEXT }}</el-descriptions-item>
            <el-descriptions-item label="建档 / 变动">
              <span class="num">{{ formatListTime(detail.wallet.createTime) }} → {{ formatListTime(detail.wallet.updateTime) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="sub-title">最近 10 条流水</h4>
          <el-table :data="detail.wallet.recentTransactions || []" size="small">
            <el-table-column label="时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag :type="txTypeTag(row.type)" effect="plain" size="small">{{ txTypeText(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column align="right" label="变动额" width="100">
              <template #default="{ row }">
                <span class="num" :class="netClass(row.amount)">{{ signedAmount(row.amount) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="余额" width="100">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.balanceAfter) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.remark || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="还没有一笔流水" :image-size="60" />
            </template>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="detail.visible = false">关闭</el-button>
        <el-button
          v-if="detail.wallet"
          type="warning"
          @click="openAdjust(detail.wallet, detail.userId)"
        >
          调整余额
        </el-button>
      </template>
    </el-dialog>

    <!-- 调账：后台唯一改别人钱的入口，所以把「改完会变成几」当场算给对方看 -->
    <el-dialog v-model="adjust.visible" title="调整余额" width="460px">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="用户">
          {{ adjust.row?.nickname || DELETED_NAME }} #{{ adjust.userId }}
        </el-form-item>
        <el-form-item label="当前余额">
          <span class="num strong">{{ formatNumber(adjust.row?.balance) }}</span>
        </el-form-item>
        <el-form-item label="变动额">
          <el-input v-model="adjust.delta" type="number" placeholder="正数加、负数扣" />
          <div class="muted tip">
            调整后约 {{ formatNumber(previewBalance) }} 积分。扣减不允许把余额调成负数——
            那条不变量是账本可信赖的前提，给管理员开后门等于取消它。
          </div>
        </el-form-item>
        <el-form-item label="事由">
          <el-input
            v-model="adjust.reason"
            :maxlength="80"
            placeholder="必填，会连同操作人一起写进流水备注"
            show-word-limit
            type="textarea"
            :rows="2"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjust.visible = false">取消</el-button>
        <el-button :loading="adjust.submitting" type="warning" @click="submitAdjust">确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adjustWalletBalance,
  fetchGiftCatalog,
  fetchGiftRecordPage,
  fetchWalletDetail,
  fetchWalletOverview,
  fetchWalletPage,
  fetchWalletTxPage,
  setGiftStatus
} from '@/api/admin'
import { EMPTY_TEXT, displayValue, formatDateTime, formatListTime, formatNumber } from '@/utils/format'

/**
 * 钱包与礼物（四期第五批）。
 *
 * 四个页签是同一条钱路上的四种切法：谁有多少（台账）、每一笔怎么动的（账本流水）、
 * 礼物流向（送礼流水）、礼簿本身（目录）。顶部那八个数字是全站口径，与页签里的筛选条件无关，
 * 所以它只人手刷新——那几个 SUM 走的是全表扫描，轮询等于把 COUNT 直接打到库上。
 *
 * 全页唯一的危险按钮是「调账」，因此它被做成一个会当场算出结果的对话框：
 * 当前余额、变动额、调整后余额三行同屏，比事后看流水便宜一次后悔。
 */

/** 用户注销后 im_user 那行没了，但钱包行与流水行还在：说「已注销」而不是画个空 */
const DELETED_NAME = '已注销用户'

const TX_TYPES = [
  { value: 'recharge', label: '充值' },
  { value: 'gift', label: '送礼' },
  { value: 'checkin', label: '签到' },
  { value: 'adjust', label: '后台调整' },
  { value: 'transfer', label: '转账' },
  { value: 'packet', label: '红包' },
  { value: 'refund', label: '红包退回' },
  { value: 'live_pay', label: '付费入场' }
]

const tab = ref('wallet')
const overview = ref(null)
const overviewLoading = ref(false)

const wallets = ref([])
const walletTotal = ref(0)
const walletLoading = ref(false)
const walletQuery = reactive({ keyword: '', userId: '', minBalance: '', current: 1, size: 20 })

const txs = ref([])
const txTotal = ref(0)
const txLoading = ref(false)
const txLoaded = ref(false)
const txQuery = reactive({ userId: '', type: undefined, keyword: '', minusOnly: false, current: 1, size: 20 })
const txRange = ref(null)

const giftRecords = ref([])
const giftRecordTotal = ref(0)
const giftRecordLoading = ref(false)
const giftRecordLoaded = ref(false)
const giftRecordQuery = reactive({ roomId: '', senderId: '', receiverId: '', current: 1, size: 20 })
const giftRecordRange = ref(null)

const gifts = ref([])
const giftLoading = ref(false)
const giftLoaded = ref(false)

/** el-date-picker 的范围端点：开始那天取 00:00:00、结束那天取 23:59:59，不然「今天」会漏掉一整天 */
const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

const detail = reactive({ visible: false, loading: false, loaded: false, userId: null, wallet: null })
const adjust = reactive({ visible: false, submitting: false, userId: null, row: null, delta: '', reason: '' })

const previewBalance = computed(() => {
  const base = Number(adjust.row?.balance ?? 0)
  const delta = Number(adjust.delta)
  return Number.isFinite(delta) && adjust.delta !== '' ? base + delta : base
})

async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = await fetchWalletOverview()
  } catch {
    // 接口层已弹提示：数字取不到时保留上一次的，别让一屏空白替一条错误说话
  } finally {
    overviewLoading.value = false
  }
}

/** 只把纯数字送进后端那些 Long 字段：塞一句昵称会在参数绑定阶段就报错，而报错信息看不出是哪一格 */
function digitsOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

function numberOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

async function loadWallets() {
  walletLoading.value = true
  try {
    const page = await fetchWalletPage({
      keyword: walletQuery.keyword || undefined,
      userId: digitsOrUndefined(walletQuery.userId),
      minBalance: numberOrUndefined(walletQuery.minBalance),
      current: walletQuery.current,
      size: walletQuery.size
    })
    wallets.value = page.records || []
    walletTotal.value = Number(page.total) || 0
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果
  } finally {
    walletLoading.value = false
  }
}

function reloadWallets() {
  walletQuery.current = 1
  loadWallets()
}

async function loadTxs() {
  txLoading.value = true
  try {
    const page = await fetchWalletTxPage({
      userId: digitsOrUndefined(txQuery.userId),
      type: txQuery.type || undefined,
      keyword: txQuery.keyword || undefined,
      minusOnly: txQuery.minusOnly || undefined,
      startTime: txRange.value?.[0] || undefined,
      endTime: txRange.value?.[1] || undefined,
      current: txQuery.current,
      size: txQuery.size
    })
    txs.value = page.records || []
    txTotal.value = Number(page.total) || 0
    txLoaded.value = true
  } catch {
    // 同上
  } finally {
    txLoading.value = false
  }
}

function reloadTxs() {
  txQuery.current = 1
  loadTxs()
}

async function loadGiftRecords() {
  giftRecordLoading.value = true
  try {
    const page = await fetchGiftRecordPage({
      roomId: digitsOrUndefined(giftRecordQuery.roomId),
      senderId: digitsOrUndefined(giftRecordQuery.senderId),
      receiverId: digitsOrUndefined(giftRecordQuery.receiverId),
      startTime: giftRecordRange.value?.[0] || undefined,
      endTime: giftRecordRange.value?.[1] || undefined,
      current: giftRecordQuery.current,
      size: giftRecordQuery.size
    })
    giftRecords.value = page.records || []
    giftRecordTotal.value = Number(page.total) || 0
    giftRecordLoaded.value = true
  } catch {
    // 直播模块没装配时后端会明确报错而不是回空列表，这里只需保留上一次结果
  } finally {
    giftRecordLoading.value = false
  }
}

function reloadGiftRecords() {
  giftRecordQuery.current = 1
  loadGiftRecords()
}

async function loadGifts() {
  giftLoading.value = true
  try {
    gifts.value = (await fetchGiftCatalog()) || []
    giftLoaded.value = true
  } catch {
    // 同上
  } finally {
    giftLoading.value = false
  }
}

/** 三个非默认页签首次点开才取数：一屏四个列表同时打四个请求，其中两个多半这一趟不会看 */
function onTabChange(name) {
  if (name === 'tx' && !txLoaded.value) {
    loadTxs()
  } else if (name === 'giftRecord' && !giftRecordLoaded.value) {
    loadGiftRecords()
  } else if (name === 'gift' && !giftLoaded.value) {
    loadGifts()
  }
}

async function openWallet(userId) {
  detail.userId = userId
  detail.wallet = null
  detail.loaded = false
  detail.visible = true
  detail.loading = true
  try {
    detail.wallet = await fetchWalletDetail(userId)
    detail.loaded = true
  } catch {
    // 拉失败时保留空态：档案是附加信息，列表那一行才是事实来源
  } finally {
    detail.loading = false
  }
}

function openAdjust(row, userId = undefined) {
  adjust.userId = userId ?? row.userId
  adjust.row = row
  adjust.delta = ''
  adjust.reason = ''
  adjust.visible = true
}

async function submitAdjust() {
  const delta = Number(adjust.delta)
  if (!Number.isFinite(delta) || delta === 0 || !Number.isInteger(delta)) {
    ElMessage.warning('请输入非零整数变动额')
    return
  }
  if (!String(adjust.reason || '').trim()) {
    ElMessage.warning('请填写调账事由')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将 #${adjust.userId} 的余额 ${formatNumber(adjust.row?.balance)} 调整为 ` +
        `${formatNumber(previewBalance.value)} 积分（${delta > 0 ? '入账' : '扣减'} ${Math.abs(delta)}）。` +
        `这一步会立刻产生一条 type=adjust 的流水，事后无法撤销，只能再调回去。`,
      '确认调整余额',
      { confirmButtonText: '确认调整', cancelButtonText: '取消', type: 'error' }
    )
  } catch {
    return
  }
  adjust.submitting = true
  try {
    const updated = await adjustWalletBalance(adjust.userId, delta, adjust.reason.trim())
    // 后端带回调整后的最新档案，就地刷那一行，不再重拉整页
    if (updated) {
      const row = wallets.value.find((item) => item.userId === updated.userId)
      if (row) {
        Object.assign(row, updated)
      }
      if (detail.visible && detail.wallet?.userId === updated.userId) {
        detail.wallet = updated
      }
    }
    adjust.visible = false
    ElMessage.success('余额已调整')
  } catch {
    // 余额不足（会被后端拦成「扣减超出余额」）等已由接口层弹提示
  } finally {
    adjust.submitting = false
  }
}

async function toggleGift(row, onShelf) {
  try {
    const updated = await setGiftStatus(row.giftId, onShelf)
    if (updated) {
      Object.assign(row, updated)
    } else {
      loadGifts()
    }
  } catch {
    // 接口层已弹提示（礼物 ID 不存在回 13003）
  }
}

function txTypeText(type) {
  return TX_TYPES.find((item) => item.value === type)?.label || type || EMPTY_TEXT
}

/**
 * 类型标签的颜色：入账类绿、扣减类红、后台调整黄。
 *
 * 按「钱往哪边走」上色而不是按类型上色，是因为读这一屏的人关心的是「今天谁的钱少了」，
 * 而不是这是第几号枚举。adjust 单独一种颜色是刻意醒目——它是唯一一条不是用户自己动的行。
 */
function txTypeTag(type) {
  switch (type) {
    case 'recharge':
    case 'checkin':
    case 'refund':
      return 'success'
    case 'adjust':
      return 'warning'
    case 'gift':
    case 'transfer':
    case 'packet':
    case 'live_pay':
      return 'danger'
    default:
      return 'info'
  }
}

/** 带符号金额：0 也写「+0」而不是「0」，账本里一个不带方向的 0 会让人以为是显示故障 */
function signedAmount(value) {
  if (value === null || value === undefined || value === '') {
    return EMPTY_TEXT
  }
  const num = Number(value)
  if (!Number.isFinite(num)) {
    return String(value)
  }
  const text = formatNumber(Math.abs(num))
  return num > 0 ? `+${text}` : num < 0 ? `-${text}` : `+${text}`
}

function netClass(value) {
  const num = Number(value)
  if (!Number.isFinite(num) || num === 0) {
    return ''
  }
  return num > 0 ? 'in' : 'out'
}

function kindText(kind) {
  if (kind === 'effect') {
    return '特效'
  }
  if (kind === 'normal') {
    return '普通'
  }
  return kind || EMPTY_TEXT
}

/**
 * ref_id 的语义随类型变（ImConstants 里那段注释是唯一口径）：转账存对方用户 ID、
 * 红包与退回存红包 ID、送礼与入场费存房间 ID。原样贴一串雪花 ID 等于没贴，
 * 所以按类型给它一个前缀，让读的人知道这个数字该往哪张表里去查。
 */
function refText(row) {
  if (row.refId === null || row.refId === undefined) {
    return EMPTY_TEXT
  }
  switch (row.type) {
    case 'transfer':
      return `对方 #${row.refId}`
    case 'packet':
    case 'refund':
      return `红包 #${row.refId}`
    case 'gift':
    case 'live_pay':
      return `房间 #${row.refId}`
    default:
      return `#${row.refId}`
  }
}

onMounted(() => {
  loadOverview()
  loadWallets()
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.mb {
  margin-bottom: 16px;
}

.hint {
  margin-bottom: 16px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kpi-value {
  font-size: 26px;
  font-weight: 600;
}

.kpi-value.warning {
  color: #e6a23c;
}

.overview {
  margin-bottom: -8px;
}

.stack {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.filters .grow {
  flex: 1;
}

.keyword {
  width: 220px;
}

.status {
  width: 130px;
}

.uid {
  width: 160px;
}

.range {
  width: 340px;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.strong {
  font-weight: 600;
}

.in {
  color: #67c23a;
}

.out {
  color: #f56c6c;
}

.gift-icon {
  font-size: 18px;
  margin-right: 4px;
}

.block {
  margin-bottom: 16px;
}

.sub-title {
  align-items: center;
  display: flex;
  font-size: 14px;
  margin: 0 0 8px;
}

.tip {
  line-height: 1.6;
  margin-top: 4px;
}

.hint-icon {
  color: #909399;
  margin-left: 2px;
}
</style>
