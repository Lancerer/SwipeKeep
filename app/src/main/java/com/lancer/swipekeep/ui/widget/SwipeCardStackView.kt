package com.lancer.swipekeep.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.customview.widget.ViewDragHelper
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.lancer.swipekeep.R
import com.lancer.swipekeep.core.Haptics
import com.lancer.swipekeep.core.SoundPlayer
import com.lancer.swipekeep.domain.model.PhotoItem
import com.lancer.swipekeep.domain.model.SwipeDecision
import kotlin.math.abs
import kotlin.math.sign

/**
 * 卡片堆。产品核心，也是全工程唯一「靠读代码验证不了」的东西 —— 手感只能上手滑。
 *
 * ## 为什么是 4 张轮转缓冲，而不是 1 个复用节点
 *
 * 单节点方案在卡片飞出后必须「吸附归零 → index++」，而吸附的那一刻该 View 是屏幕上唯一的
 * 东西，于是下一张照片会**在半空闪一下**。4 张轮转把这件事变成不可见的：
 * 飞出后把该 View 移到缓冲**末尾**（画在最底层、且被另外 3 张盖住），此时把它的位移与旋转
 * 归零谁也看不见。同时它保留着自己的 View 实例，撤销时直接从记录的位置弹回来，
 * 不需要额外的「幽灵卡片」。
 *
 * ## 两套坐标系，只准在释放的那一刻换算一次
 *
 * 拖拽期间位移活在 **layout 位置**（`left/top`，由 [ViewDragHelper] 驱动），旋转与印章由位移派生；
 * 释放瞬间把 layout 偏移**换算进 translation**，从此所有动画只动 translation。
 * 混用两套坐标系是「松手时跳一下」的唯一来源。
 *
 * ## 后排角度必须是确定性派生
 *
 * [peekRotation] 从 `(volumeName, id)` 哈希出来，**绝不在 `onLayout` 里调 `Random`** ——
 * 否则每一帧布局都会换一个角度（抖动），撤销回来时角度还跟刚才不一样。
 */
class SwipeCardStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 卡片堆需要外部提供的东西。凑成一个对象，是为了让 [bind] 的签名不随阶段 4/5 继续膨胀。 */
    class CardDeps(
        /** `PhotoItem` 不持 URI（`domain/` 要能在纯 JVM 上跑），所以由外部按 volume 现拼。 */
        val uriOf: (PhotoItem) -> Uri,
        val imageLoader: coil.ImageLoader,
        val soundPlayer: SoundPlayer,
    )

    interface Listener {
        /** 一次判定已经落定、动画刚结束。`exitX/exitY` 是卡片离开时的最终位移，撤销要用它。 */
        fun onSwiped(item: PhotoItem, decision: SwipeDecision, exitX: Float, exitY: Float)

        /** 拖拽或动画进行中 —— 三个控制按钮要在这期间锁住。 */
        fun onBusyChanged(busy: Boolean)
    }

    var listener: Listener? = null

    /**
     * 轮转缓冲。**下标 0 是顶卡**，同时也就是画在最上层的那张
     * （子 View 的绘制顺序由 [syncChildOrder] 保证，不是靠 addView 的先后）。
     */
    private val cards = arrayOfNulls<PhotoCardView>(BUFFER)

    /**
     * 每个槽位**当前绑着哪张照片、以及是按顶卡尺寸还是后排尺寸解的码**。
     * 与 [cards] 一起参与轮转，用来回答「这张卡需要重新绑定吗」——
     * 有了它，一次滑动只需要重绑 2 张，而不是把 4 张全部重解一遍。
     */
    private val bound = arrayOfNulls<Bound>(BUFFER)

    private data class Bound(val item: PhotoItem, val isFront: Boolean)

    private var queue: List<PhotoItem> = emptyList()
    private var cursor = 0
    private var deps: CardDeps? = null

    /** 顶卡的静止 left/top。用 Int：释放时要把 layout 偏移**精确**换进 translation，差 1px 都会看出来。 */
    private var restLeft = 0
    private var restTop = 0

    private var isDragging = false
    private var isAnimating = false
    private var reportedBusy = false

    private val designCardW = resources.getDimension(R.dimen.card_w)
    private val designCardH = resources.getDimension(R.dimen.card_h)
    private val peekY = resources.getDimension(R.dimen.card_stack_peek_y)
    private val arcUp = resources.getDimension(R.dimen.swipe_arc_up)
    private val arcDown = resources.getDimension(R.dimen.swipe_arc_down)

    /**
     * 「甩」的速度门槛，按 dp/s 定义再由密度换算成 px/s。
     *
     * 不用 `ViewConfiguration.scaledMinimumFlingVelocity`（在 440dpi 上只有 ~137px/s）：
     * 那太容易被一次慢速拖拽的松手速度越过，结果是「我只是慢慢拖回来，它却飞了」。
     * 200dp/s 大约是一次刻意甩动的下限，比慢速拖拽高一个数量级。
     */
    private val minFlingVelocity = MIN_FLING_DP_PER_SEC * resources.displayMetrics.density

    private var childWidth = 0
    private var childHeight = 0

    /**
     * 「后排露出深度」的驱动量：`0` = 静止（后排只露一条边），`1` = 第 1 张后排正好走到顶卡位。
     *
     * 只用一个 0..1 的标量驱动整个后排，而不是给每张卡各算一套动画：拖拽中、松手回弹、
     * 抛飞三段走的都是**同一个公式**（见 [applyBackSlotTransform]），中途不可能出现
     * 「算到一半换了套坐标」的跳变。
     *
     * 类型是 `FloatValueHolder`：`SpringAnimation` 要直接读写这个值本身，
     * 拿它构造的弹簧就不需要再声明一个 `View` 属性。
     */
    private val peek = FloatValueHolder(0f)

    /** 回弹时驱动 [peek] 归零的那个弹簧。新手势一开始就要把它掐掉。 */
    private var peekSpring: SpringAnimation? = null

    private val dragHelper = ViewDragHelper.create(this, 1f, object : ViewDragHelper.Callback() {

        /** 只有顶卡能被拖；动画期间不接受新手势（否则 280ms 抛飞里的快速双击会打乱状态）。 */
        override fun tryCaptureView(child: View, pointerId: Int): Boolean =
            hasTopCard && !isAnimating && !isDragging && child === cards[0]

        override fun getViewHorizontalDragRange(child: View): Int = child.width

        override fun getViewVerticalDragRange(child: View): Int = child.height

        /** 返回原值 = 不夹取，卡片完全跟手。 */
        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int = left

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int = top

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            if (!isDragging) {
                isDragging = true
                // 上一次回弹的后排可能还在归位途中，先掐掉它再接管。
                peekSpring?.cancel()
                peekSpring = null
                publishBusy()
            }
            val offset = offsetX(changedView)
            applyVisualForOffset(changedView, offset)
            drivePeek(dragProgress(offset), spring = false)
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val view = releasedChild as? PhotoCardView ?: return
            val dx = offsetX(view)
            val dy = offsetY(view)

            // 上滑「待定」的闸门：必须**明显**向上、而且压过横向一倍以上，
            // 否则一次「斜着往左上」的慢拖会被读成待定。计划书自己写了「手势不冲突才加」，
            // 这里先给足余量；真机上若仍误触，就退回控制行里的按钮形式。
            val upGate = dy < -UNDECIDED_MIN_HEIGHT_FRACTION * height &&
                abs(dy) > UNDECIDED_ASPECT * abs(dx)
            if (upGate) {
                commitSwipe(view, SwipeDecision.UNDECIDED)
                return
            }

            // 位移过阈值，或者「甩」出去了。甩的方向必须与已经产生的位移一致 ——
            // 否则回拉时手指的瞬间速度会把卡片甩向反方向。
            val flung = abs(xvel) > minFlingVelocity && (dx == 0f || sign(xvel) == sign(dx))
            if (abs(dx) > throwThreshold() || flung) {
                commitSwipe(view, SwipeDecision.forHorizontal(dx))
            } else {
                springBack(view)
            }
        }
    })

    /**
     * 「还有顶卡可滑」。
     *
     * 判据是**队列里还有照片**，而不是 `cards[0] != null` —— 队列滑完之后顶卡槽位的 View
     * 还在（只是 INVISIBLE 了，好让撤销能把它弹回来），只看 View 是否为空会让用户
     * 拖到一张看不见的卡片。
     */
    private val hasTopCard: Boolean get() = cards[0] != null && itemAt(0) != null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || !hasTopCard) return false
        return dragHelper.shouldInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !hasTopCard) return false
        dragHelper.processTouchEvent(event)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 用 abort 而不是 cancel：`cancel()` **不会**把 ViewDragHelper 内部的 dragState
        // 复位（只有 setDragState(STATE_IDLE) 才会，而 cancel 只清 pointer/velocity）。
        // 视图树没了还留着 DRAGGING 状态，下一次 attach 的手势会从错误的状态开始。
        // 两者对「会不会误触发一次松手」都是安全的：cancel 本身不回调 onViewReleased。
        dragHelper.abort()
        // 视图树都没了，没必要再为一个 float 跑动画（它每帧还会去写后排卡的位置）。
        peekSpring?.cancel()
        peekSpring = null
    }

    // ---------------------------------------------------------------- 对外接口

    /**
     * 绑定队列。[startIndex] 用来在视图重建后**回到之前的进度** —— 进程没死、只是 Fragment
     * 重建时，ViewModel 里的已看张数还在，卡片堆必须跟上，否则会从头再滑一遍。
     */
    fun bind(queue: List<PhotoItem>, startIndex: Int, deps: CardDeps) {
        this.queue = queue
        this.deps = deps
        cursor = startIndex.coerceIn(0, queue.size)

        removeAllViews()
        cards.fill(null)
        bound.fill(null)

        // 只给「真的有照片」的槽位建 View。队列比缓冲短的时候（比如只有 2 张），
        // 剩下的槽位保持 null，轮转时 null 会跟着一起搬，正好表达「这里没卡」。
        for (slot in 0 until BUFFER) {
            if (itemAt(slot) != null) {
                cards[slot] = PhotoCardView(context).also { addView(it) }
            }
        }

        syncChildOrder()
        syncAllSlots()
        resetPeek()
        requestLayout()
        preloadAhead()
        publishBusy()
    }

    /**
     * 撤销：把上一步飞出去的那张从它**离开的位置**弹回来。
     *
     * 关键顺序：先在「被其他 3 张盖住」的后排位置把位移设回记录的 exit 点，**再**轮转回退。
     * 轮转之后它就成了顶卡，而那时它已经在屏幕外了 —— 所以全程没有一帧能看到它瞬移。
     * 反过来写（先轮转再设位移）就会看到它当着用户的面跳到屏幕外。
     */
    fun undo(exitX: Float, exitY: Float) {
        if (isBusy || cursor <= 0) return
        val incoming = cards[BUFFER - 1] ?: return

        incoming.translationX = exitX
        incoming.translationY = exitY

        rotateBackward()
        syncChildOrder()
        syncAllSlots()

        val front = cards[0] ?: return
        front.scaleX = 1f
        front.scaleY = 1f
        // 角度与印章跟着位移走（此时位移就是 exit 点），否则它飞回来时是「平移但没角度」的怪样子
        applyVisualForOffset(front, offsetX(front))
        // 原来的顶卡这时退到后排，要拿回后排该有的缩放与角度；同时保证后排停在静止层
        resetPeek()
        requestLayout()
        invalidate()

        isAnimating = true
        publishBusy()
        Haptics.tick(this)
        runSpring(front) {
            front.translationX = 0f
            front.translationY = 0f
            front.rotation = 0f
            front.resetStamp()
            isAnimating = false
            publishBusy()
        }
    }

    /** 三个控制按钮走这里，与手势滑动共用同一条抛飞路径。 */
    fun swipeProgrammatically(decision: SwipeDecision) {
        if (isBusy) return
        val view = cards[0] ?: return
        if (itemAt(0) == null) return
        commitSwipe(view, decision)
    }

    val isBusy: Boolean get() = isDragging || isAnimating

    // ---------------------------------------------------------------- 抛飞与回弹

    /**
     * 判定落定：换算坐标系、给反馈、抛出去。
     *
     * 换算（`translation += layoutOff; offsetLeftAndRight(-layoutOff)`）之后视觉位置**完全不变**，
     * 但从此 layout 位置回到静止点，动画只动 translation。少了这一步，动画与
     * [onLayout] 会各拉一套坐标，表现就是松手时卡片先弹回原位再飞出去。
     *
     * 不需要知道手指松在哪（`dx/dy`）：**起点就是 `view.translationX/Y`**
     * （换算完之后它就等于拖拽位移），**方向由 [decision] 决定** ——
     * 两处都能各自拿到，再传进来只会多一个可能与事实不符的副本。
     */
    private fun commitSwipe(view: PhotoCardView, decision: SwipeDecision) {
        val item = itemAt(0) ?: return
        val target = deps ?: return

        isDragging = false
        isAnimating = true
        publishBusy()
        convertLayoutOffsetToTranslation(view)

        // 后排立刻前进到顶卡位。这一步**看不见**：前进意味着钻到顶卡背后，
        // 而顶卡此刻还盖在原处；等它飞出屏幕时后排已经在位了，不需要补位动画。
        // 用瞬时而不是动画，是因为「后退一层」的位移最多 8dp，被顶卡完全遮住。
        drivePeek(1f, spring = false)

        // 手感与声音立刻给。等动画跑完（280ms）再响，手指早就离开屏幕了。
        when (decision) {
            SwipeDecision.DELETE -> {
                Haptics.reject(view)
                target.soundPlayer.playDelete()
            }
            // 待定既不是拒绝也不是确认：给一声轻点就够了，不出音。
            SwipeDecision.UNDECIDED -> Haptics.tick(view)
            SwipeDecision.KEEP -> {
                Haptics.confirm(view)
                target.soundPlayer.playKeep()
            }
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val startX = view.translationX
        val startY = view.translationY

        // 出场点：横向两种抛到 1.5 倍宽度外（整张卡出屏）；纵向那种直着窜出上边界。
        // 待定的横向分量是 0，所以它不会一边上升一边斜着飞。
        val exitX = decision.horizontalExitSign * width * EXIT_DISTANCE_FACTOR
        val exitY = if (decision.isVertical) -height * EXIT_VERTICAL_FACTOR else startY + arcDown

        val xTrack = PropertyValuesHolder.ofKeyframe(
            View.TRANSLATION_X,
            Keyframe.ofFloat(0f, startX),
            Keyframe.ofFloat(1f, exitX),
        )
        val yTrack = if (decision.isVertical) {
            // 上滑是一条直线往上：没有「先抛起再落下」那回事，那是横向甩出去的弧线。
            PropertyValuesHolder.ofKeyframe(
                View.TRANSLATION_Y,
                Keyframe.ofFloat(0f, startY),
                Keyframe.ofFloat(1f, exitY),
            )
        } else {
            PropertyValuesHolder.ofKeyframe(
                View.TRANSLATION_Y,
                Keyframe.ofFloat(0f, startY),
                // 中点抬高 = 设计图 02 的「先抛起再落下」
                Keyframe.ofFloat(0.5f, startY - arcUp),
                Keyframe.ofFloat(1f, exitY),
            )
        }

        val animator = ObjectAnimator.ofPropertyValuesHolder(view, xTrack, yTrack).apply {
            duration = THROW_DURATION_MS
            // 线性：关键帧自己已经表达了弧线，再叠一个缓动器会把弧线拉歪。
            interpolator = LinearInterpolator()
            // 旋转与印章继续跟随位移。走同一个函数，保证拖拽段与动画段是同一套视觉。
            addUpdateListener { applyVisualForOffset(view, offsetX(view)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishSwipe(view, item, decision)
                }
            })
        }
        animator.start()
    }

    /** 抛飞结束：轮转缓冲、把飞出去那张收进后排、通知进度。 */
    private fun finishSwipe(view: PhotoCardView, item: PhotoItem, decision: SwipeDecision) {
        // 位移要在归零之前读出来 —— 这是撤销时的起始点。
        val exitX = view.translationX
        val exitY = view.translationY

        rotateForward()
        // 先重排绘制顺序：此刻这张卡已经画在最底层，被另外 3 张盖住，
        // 所以接下来把它的位移/旋转/印章归零，屏幕上看不到任何变化。
        syncChildOrder()
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        view.resetStamp()

        syncAllSlots()
        // 后排退回各自的层（`peek` 归零）。它退回一层的动作与刚才轮转掉的槽位正好抵消：
        // 轮转前第 2 张在「有效层深 1」，轮转后它成了第 1 张、有效层深仍是 1 —— 屏幕上看不出变化。
        resetPeek()
        promoteFrontCard()
        preloadAhead()

        isAnimating = false
        publishBusy()
        listener?.onSwiped(item, decision, exitX, exitY)
        requestLayout()
        invalidate()
    }

    /**
     * 把新顶卡的缩放/位移/角度对齐到静止态。
     *
     * 阶段 3 这里会看到「上移 16dp + 角度 ±5°→0」的一跳，因为后排是**瞬时**替补上来的。
     * 现在后排跟着拖拽量渐进前进（见 [applyBackSlotTransform]），抛飞那一刻它已经在
     * 顶卡位上了，所以这里只是把数字对一遍，屏幕上看不到任何变化。
     * 留着它是因为 `undo()` 之后顶卡的位移由谁来归零不能含糊。
     */
    private fun promoteFrontCard() {
        val front = cards[0] ?: return
        front.scaleX = 1f
        front.scaleY = 1f
        front.translationX = 0f
        front.translationY = 0f
        front.rotation = 0f
    }

    /** 没到阈值：卡片弹回原位，后排也退回各自的层。 */
    private fun springBack(view: PhotoCardView) {
        isDragging = false
        isAnimating = true
        publishBusy()
        convertLayoutOffsetToTranslation(view)
        Haptics.tick(view)
        // 卡片归位与后排退回要**都**结束才算回弹完成，否则 isBusy 会提前变 false，
        // 用户能在卡片还没停稳时抓到它。
        val gate = Gate(2) {
            view.rotation = 0f
            view.resetStamp()
            isAnimating = false
            publishBusy()
        }
        runSpring(view) { gate.arrive() }
        drivePeek(0f, spring = true) { gate.arrive() }
    }

    /**
     * 把 [peek] 驱到 [target]。
     *
     * `spring = false` 是瞬时（拖拽跟手、以及抛飞那一瞬），`spring = true` 走与卡片回弹
     * **同一档阻尼与刚度**的弹簧 —— 松手时后排与卡片必须同一种手感，否则会有一张走得比另一张快。
     */
    private fun drivePeek(target: Float, spring: Boolean, onEnd: (() -> Unit)? = null) {
        peekSpring?.cancel()
        peekSpring = null
        if (!spring) {
            peek.setValue(target)
            applyBackSlotTransforms()
            onEnd?.invoke()
            return
        }
        peekSpring = SpringAnimation(peek).apply {
            setSpring(
                SpringForce(target).apply {
                    dampingRatio = SPRING_DAMPING_RATIO
                    stiffness = SpringForce.STIFFNESS_LOW
                },
            )
            addUpdateListener { _, _, _ -> applyBackSlotTransforms() }
            addEndListener { _, _, _, _ -> onEnd?.invoke() }
            start()
        }
    }

    /** 后排退回各自的层，并掐掉还在跑的弹簧。绑定、轮转、撤销之后都要调。 */
    private fun resetPeek() {
        drivePeek(0f, spring = false)
    }

    /** 拖拽比例：位移占判定阈值的比，钳到 0..1。后排照这个比例前进。 */
    private fun dragProgress(dx: Float): Float {
        val threshold = throwThreshold()
        if (threshold <= 0f) return 0f
        return (abs(dx) / threshold).coerceIn(0f, 1f)
    }

    /**
     * 数够 [count] 次才执行 [block]。用来表达「好几个动画全跑完，这件事才算结束」。
     *
     * 幂等保护（`remaining > 0`）是必要的：弹簧被 `cancel()` 时也会回调结束监听，
     * 而 `cancel` 是「新手势接管」的常规动作，不是异常路径。
     */
    private class Gate(private val count: Int, private val block: () -> Unit) {
        private var remaining = count

        fun arrive() {
            if (remaining > 0 && --remaining == 0) block()
        }
    }

    /**
     * 把 layout 偏移换算进 translation。**视觉位置完全不变**，
     * 但之后所有动画都只操作 translation，不再与 [onLayout] 抢同一个坐标系。
     */
    private fun convertLayoutOffsetToTranslation(view: View) {
        val offX = view.left - restLeft
        val offY = view.top - restTop
        view.translationX += offX
        view.translationY += offY
        view.offsetLeftAndRight(-offX)
        view.offsetTopAndBottom(-offY)
    }

    /**
     * x / y 各一个弹簧，独立跑；两个都结束了才算回弹完成。
     *
     * `SpringAnimation` 就是 Compose `spring()` 的 View 版原型（同一个 `androidx.dynamicanimation`），
     * 用它而不是自己写插值：阻尼比与刚度是有物理含义的参数，调起来有依据。
     */
    private fun runSpring(view: View, onSettled: () -> Unit) {
        val remaining = intArrayOf(2)
        fun done() {
            if (--remaining[0] == 0) onSettled()
        }
        val force = SpringForce(0f).apply {
            dampingRatio = SPRING_DAMPING_RATIO
            stiffness = SpringForce.STIFFNESS_LOW
        }
        SpringAnimation(view, DynamicAnimation.TRANSLATION_X).apply {
            spring = force
            addUpdateListener { _, _, _ -> applyVisualForOffset(view, offsetX(view)) }
            // 结束回调是 4 个参数（animation, canceled, value, velocity）——
            // 不是常见的 (animation, canceled) 两个。
            addEndListener { _, _, _, _ -> done() }
            start()
        }
        // 刚度对象各自一个：SpringForce 在一次动画里会被写入中间量，两个动画共用会互相踩。
        val forceY = SpringForce(0f).apply {
            dampingRatio = SPRING_DAMPING_RATIO
            stiffness = SpringForce.STIFFNESS_LOW
        }
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Y).apply {
            spring = forceY
            addEndListener { _, _, _, _ -> done() }
            start()
        }
    }

    // ---------------------------------------------------------------- 轮转缓冲

    private fun rotateForward() {
        val firstCard = cards[0]
        val firstBound = bound[0]
        for (i in 0 until BUFFER - 1) {
            cards[i] = cards[i + 1]
            bound[i] = bound[i + 1]
        }
        cards[BUFFER - 1] = firstCard
        bound[BUFFER - 1] = firstBound
        cursor++
    }

    private fun rotateBackward() {
        val lastCard = cards[BUFFER - 1]
        val lastBound = bound[BUFFER - 1]
        for (i in BUFFER - 1 downTo 1) {
            cards[i] = cards[i - 1]
            bound[i] = bound[i - 1]
        }
        cards[0] = lastCard
        bound[0] = lastBound
        cursor--
    }

    /**
     * 让子 View 的绘制顺序与「槽位 0 是最上层」一致。
     *
     * `FrameLayout` 按 addView 的先后绘制，后加的盖住先加的，所以要让槽位 0 最后入列。
     * 轮转确实会改变谁该在最上层，因此这一步不能省 —— 少了它，刚飞出去那张会盖住新的顶卡。
     */
    private fun syncChildOrder() {
        for (slot in BUFFER - 1 downTo 0) {
            cards[slot]?.let { bringChildToFront(it) }
        }
    }

    /** 把每个槽位的内容对齐到队列当前位置。轮转后必调 —— 槽位会变空也会变满。 */
    private fun syncAllSlots() {
        for (slot in 0 until BUFFER) syncSlot(slot)
    }

    /**
     * 槽位与队列对齐：该显示的显示、该空的空、该换尺寸的换尺寸。
     *
     * 不变量：**槽位需要的 View 一定已经存在**。卡片只在 [bind] 里创建，之后只在缓冲里轮转，
     * 而轮转是「整体搬家」，所以「某个槽位需要一张卡但它从没被创建过」不会发生。
     */
    private fun syncSlot(slot: Int) {
        val card = cards[slot] ?: return
        val item = itemAt(slot)
        val target = deps ?: return

        if (item == null) {
            // 只藏不清：撤销还要用它的位图，而且重绑会白跑一次解码。
            card.visibility = INVISIBLE
            return
        }
        card.visibility = VISIBLE

        val isFront = slot == 0
        val current = bound[slot]
        if (current?.item == item && current.isFront == isFront) return

        card.bind(item, target.uriOf(item), target.imageLoader, isFront)
        bound[slot] = Bound(item, isFront)
    }

    private fun applyBackSlotTransforms() {
        for (slot in 1 until BUFFER) {
            val card = cards[slot] ?: continue
            if (card.visibility != VISIBLE) continue
            applyBackSlotTransform(card, slot)
        }
    }

    /**
     * 后排卡片的位置。**跟着拖拽量走**（阶段 4 的「后排露出深度」）：
     * 顶卡被拖出 `p` 的比例，后排就整体前进 `p` 层 —— 拖到一半时第 1 张后排已经走到一半。
     *
     * 公式用「有效层深」`depth = slot - p`，于是 `p = 1` 时第 1 张后排恰好落在顶卡位
     * （`scale = 1`、`translationY = 0`），第 2 张落在第 1 层，依此类推。这正是抛飞那一刻
     * 需要的 —— 卡片飞走的瞬间后排已经在它该在的位置上，于是**不需要任何补位动画**。
     *
     * 静止态（`p = 0`）时 `depth = slot`，每层露出 8dp：缩放是**居中**的，缩掉的高度
     * 有一半跑到顶边上方（`h*(1-scale)/2`），再额外上移 `slot * 8dp`，净效果正好对设计图 01
     * 的三张卡顶边 228 / 220 / 212。
     */
    private fun applyBackSlotTransform(card: View, slot: Int) {
        val progress = peek.value
        val depth = slot - progress
        val scale = 1f - depth * SCALE_STEP
        card.scaleX = scale
        card.scaleY = scale
        val scaleTopShift = childHeight * (1f - scale) / 2f
        card.translationY = -(depth * peekY + scaleTopShift)
        card.translationX = 0f
        card.rotation = rotationForSlot(slot, progress)
    }

    /**
     * 后排角度：由 `(volumeName, id)` 稳定派生（[peekRotation]），再按 `p` 线性插值到
     * **上一层的角度**。
     *
     * 插值而不是简单地乘 `(1 - p)`：`p = 1` 时这张卡的视觉位置已经是「它上一层」了，
     * 角度就必须同样是上一层的角度，否则轮转发生的那一刻能看出角度弹了一下。
     * 第 1 层的上一层是顶卡，而顶卡的静止角度是 0。
     *
     * 角度只跟身份哈希有关、与列布局无关，所以每帧重算也不会抖。
     */
    private fun rotationForSlot(slot: Int, progress: Float): Float {
        val own = itemAt(slot)?.let(::peekRotation) ?: return 0f
        val target = if (slot <= 1) 0f else itemAt(slot - 1)?.let(::peekRotation) ?: own
        return own + (target - own) * progress
    }

    /** 每次滑动后预热后面 2 张，把解码挪出关键帧。 */
    private fun preloadAhead() {
        val target = deps ?: return
        for (offset in 1..PRELOAD_AHEAD) {
            val item = itemAt(offset) ?: continue
            PhotoCardView.preload(context, target.uriOf(item), target.imageLoader)
        }
    }

    private fun itemAt(slot: Int): PhotoItem? = queue.getOrNull(cursor + slot)

    // ---------------------------------------------------------------- 位移派生视觉

    private fun offsetX(view: View): Float = (view.left - restLeft).toFloat() + view.translationX

    private fun offsetY(view: View): Float = (view.top - restTop).toFloat() + view.translationY

    private fun throwThreshold(): Float = width * THROW_THRESHOLD_FRACTION

    /** 旋转与印章都只跟横向位移有关，且**总是**走这一个函数，拖拽段与动画段才不会各画各的。 */
    private fun applyVisualForOffset(view: View, dx: Float) {
        view.rotation = rotationFor(dx)
        (view as? PhotoCardView)?.setStamp(dx / throwThreshold())
    }

    /**
     * 位移 → 角度。45 这个系数是照着设计图 02 反推的：
     * 那张「正在左滑」的卡片位移 -115 / 画布宽 378 ≈ 0.30，角度 -14°。
     * 夹在 ±15° 内与验收项「旋转不超过 15°」一致。
     */
    private fun rotationFor(dx: Float): Float {
        val w = width.toFloat()
        if (w <= 0f) return 0f
        return ((dx / w) * ROTATION_FACTOR).coerceIn(-MAX_ROTATION, MAX_ROTATION)
    }

    /**
     * 后排角度：从 `(volumeName, id)` 稳定派生，落在 [-5°, +5°]。
     *
     * 常量取自 Knuth 的乘法哈希（2654435761 ≈ 2^32 / 黄金比例），把两个身份字段混成一个哈希，
     * 再取中间 12 位映射到角度区间。**任何 `Random` 都会让每帧布局换一次角度**。
     */
    private fun peekRotation(item: PhotoItem): Float {
        val h = (item.id * 2654435761L) xor (item.volumeName.hashCode().toLong() * 40503L)
        return ((h ushr 8) and 0xFFF) / 4095f * 10f - 5f
    }

    private fun publishBusy() {
        val busy = isBusy
        if (busy == reportedBusy) return
        reportedBusy = busy
        listener?.onBusyChanged(busy)
    }

    // ---------------------------------------------------------------- 测量与布局

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = availableSize(widthMeasureSpec, designCardW)
        val availH = availableSize(heightMeasureSpec, designCardH)

        // 按设计比例 300:400 摆放，同时不超出可用空间 —— 窄屏上卡片整体缩小，而不是被裁掉。
        val aspect = designCardW / designCardH
        val cardW = minOf(designCardW, availW, availH * aspect)
        val cardH = cardW / aspect
        childWidth = cardW.toInt()
        childHeight = cardH.toInt()

        for (slot in 0 until BUFFER) {
            cards[slot]?.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }

        setMeasuredDimension(
            resolveSize((designCardW + paddingLeft + paddingRight).toInt(), widthMeasureSpec),
            resolveSize((designCardH + paddingTop + paddingBottom).toInt(), heightMeasureSpec),
        )
    }

    /** `UNSPECIFIED` 时 `MeasureSpec.getSize` 是 0，直接拿它算会让卡片缩成一个点。 */
    private fun availableSize(spec: Int, fallback: Float): Float =
        if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) {
            fallback
        } else {
            MeasureSpec.getSize(spec).toFloat()
        }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val centeredLeft = (right - left - childWidth) / 2
        val centeredTop = (bottom - top - childHeight) / 2
        restLeft = centeredLeft
        restTop = centeredTop

        for (slot in 0 until BUFFER) {
            val card = cards[slot] ?: continue
            // 拖拽期间绝不能重新布局顶卡：ViewDragHelper 已经把它挪到手指位置了，
            // 这里再摆一次就是「一帧抖动把卡片弹回原位」。
            if (slot == 0 && isDragging) continue

            card.layout(centeredLeft, centeredTop, centeredLeft + childWidth, centeredTop + childHeight)
            if (slot == 0) {
                // 顶卡的平移与旋转归动画/手势管，这里只保证它不带着后排的缩放。
                card.scaleX = 1f
                card.scaleY = 1f
            } else {
                applyBackSlotTransform(card, slot)
            }
        }
    }

    private companion object {
        /** 缓冲深度。4 张 = 顶卡 + 3 张后排，够盖住轮转的那个瞬间。 */
        const val BUFFER = 4

        /** 滑动超过卡片区宽度的 1/4 就算数（实施计划 §4.1）。 */
        const val THROW_THRESHOLD_FRACTION = 0.25f

        /**
         * 每往后退一层，卡片缩小 4%。**放在 Kotlin 而不是 `dimens.xml`**：
         * 它是个无单位倍率，AAPT 会存成 float，`getDimension()` 拿不了（会抛异常）。
         */
        const val SCALE_STEP = 0.04f

        /** 抛到 1.5 倍宽度外，确保整张卡都出了屏幕（屏幕宽约等于本 View 宽）。 */
        const val EXIT_DISTANCE_FACTOR = 1.5f

        /**
         * 上滑「待定」的闸门：竖向位移要超过卡片高度的 35%，且**横向的两倍以上**。
         * 后一条是防误触的关键 —— 只卡高度的话，一次「斜着往左上」的快拖会被读成待定。
         */
        const val UNDECIDED_MIN_HEIGHT_FRACTION = 0.35f
        const val UNDECIDED_ASPECT = 2f

        /** 待定是纵向抛飞：窜出上边界 1.4 倍卡高（卡片居中，1 倍就够，留点余量）。 */
        const val EXIT_VERTICAL_FACTOR = 1.4f

        const val THROW_DURATION_MS = 280L

        /** 见 [rotationFor]：由设计图位移 -115 / 宽 378 → -14° 反推。 */
        const val ROTATION_FACTOR = 45f
        const val MAX_ROTATION = 15f

        const val MIN_FLING_DP_PER_SEC = 200f

        /**
         * 0.72 = 稍微欠阻尼，回弹时有一点点过冲。1.0 是全无过冲（显得生硬），
         * 再低就开始像弹簧玩具了。
         *
         * 刚度用 `STIFFNESS_LOW`(200)：档位只有 HIGH/MEDIUM/LOW/VERY_LOW 四档，
         * 计划书里写的 `STIFFNESS_MEDIUM_LOW` 并不存在。LOW 介于 MEDIUM(1500) 与 VERY_LOW(50) 之间，
         * 正是「稳稳落回」的手感。
         */
        const val SPRING_DAMPING_RATIO = 0.72f

        const val PRELOAD_AHEAD = 2
    }
}
