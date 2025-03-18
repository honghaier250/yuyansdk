package com.yuyan.imemodule.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.CandidatesBarAdapter
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.InputBaseContainer
import com.yuyan.imemodule.keyboard.manager.CustomLinearLayoutManager
import splitties.dimensions.dp

/**
 * 候选词集装箱
 */
class CandidatesBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mRightArrowBtn: ImageView // 右边箭头按钮
    private lateinit var mMenuRightArrowBtn: ImageView
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mCandidatesMenuContainer: LinearLayout //控制菜单视图
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mIvMenuSetting: ImageView
    private lateinit var mLlContainer: LinearLayout
    private lateinit var mFlowerType: TextView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var mRVContainerMenu:RecyclerView   // 候选词栏菜单
    private lateinit var mCandidatesMenuAdapter: CandidatesMenuAdapter
    private var mMenuHeight: Int = 0
    private var mMenuPadding: Int = 0
    private var mLastMenuHeight: Int = 0
    private var activeCandNo:Int = 0

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        mMenuHeight = (instance.heightForCandidates * 0.8f).toInt()
        mMenuPadding = (instance.heightForCandidates * 0.3f).toInt()
        initMenuView()
        initCandidateView()
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized || mLastMenuHeight != mMenuHeight) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                visibility = GONE
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            mRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setPadding(mMenuPadding,0, mMenuPadding,0)
                setImageResource(R.drawable.sdk_level_list_candidates_display)
                layoutParams = LinearLayout.LayoutParams(instance.heightForCandidates, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
            }
            mRightArrowBtn.setOnClickListener { view: View ->
                when (val level = (view as ImageView).drawable.level) {
                    2 -> mCvListener.onClickClearCandidate()
                    else -> {
                        mCvListener.onClickMore(level)
                        view.drawable.setLevel(1 - level)
                    }
                }
            }
            mRVCandidates = RecyclerView(context)
            mRVCandidates.setItemAnimator(null)
            mRVCandidates.layoutManager =  CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            mRVCandidates.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener(){
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
                        DecodingInfo.activeCandidateBar = lastVisibleItemPosition
                        val itemCount = recyclerView.adapter?.itemCount
                        if (KeyboardManager.instance.currentContainer !is CandidatesContainer && itemCount != null && lastVisibleItemPosition >= itemCount - 1) {
                            DecodingInfo.nextPageCandidates
                        }
                    }
                }


            })
            this.addView(mCandidatesDataContainer)
            mLastMenuHeight = mMenuHeight
        } else {
            (mRightArrowBtn.parent as ViewGroup).removeView(mRightArrowBtn)
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
        }
        val oneHandedModSwitch = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedMod.getValue()
        if (oneHandedModSwitch && oneHandedMod == KeyboardOneHandedMod.LEFT) {
            mCandidatesDataContainer.addView(mRightArrowBtn)
            mCandidatesDataContainer.addView(mRVCandidates)
        } else {
            mCandidatesDataContainer.addView(mRVCandidates)
            mCandidatesDataContainer.addView(mRightArrowBtn)
        }
    }

    //初始化标题栏
    fun initMenuView() {
        if(!::mCandidatesMenuContainer.isInitialized || mLastMenuHeight != mMenuHeight) {
            this.removeAllViews()
            mCandidatesMenuContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mIvMenuSetting = ImageView(context).apply {
                setImageResource(R.drawable.sdk_level_candidates_menu_left)
                isClickable = true
                isEnabled = true
                setPadding(mMenuPadding, 0,mMenuPadding/2,0)
                setOnClickListener{mCvListener.onClickMenu(SkbMenuMode.SettingsMenu)}
            }
            mLlContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mFlowerType = TextView(context).apply {
                textSize = DevicesUtils.px2dip(instance.candidateTextSize)
                setTextColor(ThemeManager.activeTheme.keyTextColor)
                val paddingValue = dp(5)
                setPadding(paddingValue, paddingValue, paddingValue, paddingValue)
            }
            val flowerTypefaces = arrayOf(FlowerTypefaceMode.Mars, FlowerTypefaceMode.FlowerVine, FlowerTypefaceMode.Messy, FlowerTypefaceMode.Germinate,
                FlowerTypefaceMode.Fog,FlowerTypefaceMode.ProhibitAccess, FlowerTypefaceMode.Grass, FlowerTypefaceMode.Wind, FlowerTypefaceMode.Disabled)
            val flowerTypefacesName = resources.getStringArray(R.array.FlowerTypeface)
            if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
                mLlContainer.visibility = GONE
            } else {
                mFlowerType.text = flowerTypefacesName[flowerTypefaces.indexOf(CustomConstant.flowerTypeface)]
            }
            mFlowerType.setOnClickListener{ _: View ->
                val popupMenu = PopupMenu(context, mLlContainer).apply {
                    menuInflater.inflate(R.menu.flower_typeface_menu, menu)
                    setOnMenuItemClickListener { menuItem ->
                        val ids = listOf(R.id.flower_type_mars, R.id.flower_type_flowervine , R.id.flower_type_messy, R.id.flower_type_grminate, R.id.flower_type_fog,
                            R.id.flower_type_prohibitaccess, R.id.flower_type_grass, R.id.flower_type_wind, R.id.flower_type_disabled)
                        val position =  ids.indexOf(menuItem.itemId)
                        val select =  flowerTypefaces[position]
                        mFlowerType.text = flowerTypefacesName[position]
                        CustomConstant.flowerTypeface = select
                        if(select == FlowerTypefaceMode.Disabled){
                            mLlContainer.visibility = GONE
                        }
                        mCandidatesMenuAdapter.notifyChanged()// 刷新菜单栏
                        false
                    }
                }
                popupMenu.show()
            }
            mLlContainer.addView(mFlowerType, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            mRVContainerMenu = RecyclerView(context).apply {
                layoutManager =  CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, true)
            }
            mRVContainerMenu.setItemAnimator(null)
            mCandidatesMenuAdapter = CandidatesMenuAdapter(context)
            mCandidatesMenuAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
                val skbMenuMode = mCandidatesMenuAdapter.getMenuMode(position)
                if(skbMenuMode != null) onClickMenu(skbMenuMode, view)
            }
            mRVContainerMenu.setAdapter(mCandidatesMenuAdapter)
            mMenuRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setPadding(mMenuPadding,0, mMenuPadding,0)
                setImageResource(R.drawable.ic_menu_arrow_down)
                layoutParams = LinearLayout.LayoutParams(instance.heightForCandidates, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
            }
            mMenuRightArrowBtn.setOnClickListener { _: View ->
                mCvListener.onClickMenu(SkbMenuMode.CloseSKB)
            }
            mCandidatesMenuContainer.addView(mIvMenuSetting, LinearLayout.LayoutParams(instance.heightForCandidates, instance.heightForCandidates, 0f))
            mCandidatesMenuContainer.addView(mLlContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, instance.heightForCandidates,0f))
            mCandidatesMenuContainer.addView(mRVContainerMenu, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, mMenuHeight, 1f))
            mCandidatesMenuContainer.addView(mMenuRightArrowBtn, LinearLayout.LayoutParams(instance.heightForCandidates, instance.heightForCandidates, 0f))
            this.addView(mCandidatesMenuContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        mCandidatesMenuAdapter.notifyChanged()  // 点击下拉菜单后，需要刷新菜单栏
    }

    private fun onClickMenu(skbMenuMode: SkbMenuMode, view: View?) {
        if(skbMenuMode == SkbMenuMode.ClearClipBoard){
            val popupMenu = PopupMenu(context, view).apply {
                menuInflater.inflate(R.menu.clear_clipboard, menu)
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.clear -> {
                            mCvListener.onClickClearClipBoard()
                        }
                    }
                    false
                }
            }
            popupMenu.show()
        } else {
            mCvListener.onClickMenu(skbMenuMode)
        }
    }

    /**
     * 显示候选词
     */
    fun showCandidates() {
        val container = KeyboardManager.instance.currentContainer
        mIvMenuSetting.drawable.setLevel( if(container is InputBaseContainer) 0 else 1)
        if (container is ClipBoardContainer) {
            showViewVisibility(mCandidatesMenuContainer)
            mCandidatesMenuAdapter.items = if(container.getMenuMode() == SkbMenuMode.ClipBoard) {
                listOf(
                    // 在剪贴板界面显示的菜单项，确保AI按钮始终显示在列表中
                    menuSkbFunsPreset[SkbMenuMode.ClearClipBoard]!!,
                    menuSkbFunsPreset[SkbMenuMode.ClipBoard]!!,
                    menuSkbFunsPreset[SkbMenuMode.Phrases]!!,
                    menuSkbFunsPreset[SkbMenuMode.LockClipBoard]!!,
                    menuSkbFunsPreset[SkbMenuMode.AI]!!
                )
            } else {
                listOf(
                    // 在短语界面显示的菜单项，确保AI按钮始终显示在列表中
                    menuSkbFunsPreset[SkbMenuMode.AddPhrases]!!,
                    menuSkbFunsPreset[SkbMenuMode.ClipBoard]!!,
                    menuSkbFunsPreset[SkbMenuMode.Phrases]!!,
                    menuSkbFunsPreset[SkbMenuMode.LockClipBoard]!!,
                    menuSkbFunsPreset[SkbMenuMode.AI]!!
                )
            }
        } else if (DecodingInfo.isCandidatesListEmpty) {
            mRightArrowBtn.drawable.setLevel(0)
            showViewVisibility(mCandidatesMenuContainer)
            val mFunItems: MutableList<SkbFunItem> = mutableListOf()
            val barMenus = DataBaseKT.instance.skbFunDao().getALlBarMenu()
            for (item in barMenus) {
                val skbMenuMode = SkbMenuMode.decode(item.name)
                val skbFunItem = menuSkbFunsPreset[skbMenuMode]
                if (skbFunItem != null) {
                    mFunItems.add(skbFunItem)
                }
            }
            // 确保AI按钮始终显示在列表中，但保持在原来的位置
            if (!mFunItems.any { it.skbMenuMode == SkbMenuMode.AI }) {
                menuSkbFunsPreset[SkbMenuMode.AI]?.let { aiFunItem ->
                    mFunItems.add(aiFunItem)
                }
            }
            mCandidatesMenuAdapter.items = mFunItems
        } else {
            if (DecodingInfo.candidateSize > DecodingInfo.activeCandidateBar) mRVCandidates.layoutManager?.scrollToPosition(DecodingInfo.activeCandidateBar)
            showViewVisibility(mCandidatesDataContainer)
            mRightArrowBtn.drawable.setLevel(if (DecodingInfo.isAssociate) 2 else if (KeyboardManager.instance.currentContainer is CandidatesContainer) 1 else 0)
        }
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 显示表情
     */
    fun showEmoji() {
        showViewVisibility(mCandidatesMenuContainer)
        // 确保AI按钮在表情界面也能显示
        mCandidatesMenuAdapter.items = listOf(
            menuSkbFunsPreset[SkbMenuMode.Emoticon]!!,
            menuSkbFunsPreset[SkbMenuMode.Emojicon]!!,
            menuSkbFunsPreset[SkbMenuMode.AI]!!
        )
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DecodingInfo.isCandidatesListEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DecodingInfo.candidateSize) activeCandNo = DecodingInfo.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mCandidatesAdapter.notifyChanged()
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo -1 else 0
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    /**
     * 显示AI功能
     */
    fun showAI() {
        showViewVisibility(mCandidatesMenuContainer)
        // 获取当前的菜单项列表
        val mFunItems: MutableList<SkbFunItem> = mutableListOf()
        val barMenus = DataBaseKT.instance.skbFunDao().getALlBarMenu()
        for (item in barMenus) {
            val skbMenuMode = SkbMenuMode.decode(item.name)
            val skbFunItem = menuSkbFunsPreset[skbMenuMode]
            if (skbFunItem != null) {
                mFunItems.add(skbFunItem)
            }
        }
        // 确保AI按钮始终显示在列表中
        if (!mFunItems.any { it.skbMenuMode == SkbMenuMode.AI }) {
            menuSkbFunsPreset[SkbMenuMode.AI]?.let { aiFunItem ->
                mFunItems.add(aiFunItem)
            }
        }
        mCandidatesMenuAdapter.items = mFunItems
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 选择花漾字
     */
    fun showFlowerTypeface() {
        if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
            mLlContainer.visibility = GONE
        } else {
            CustomConstant.flowerTypeface = FlowerTypefaceMode.Mars
            mFlowerType.text = "焱暒妏"
            mLlContainer.visibility = VISIBLE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(instance.heightForCandidates, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(instance.skbWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    private fun showViewVisibility(candidatesContainer: View) {
        if(candidatesContainer === mCandidatesMenuContainer){
            mCandidatesMenuContainer.visibility = VISIBLE
            mCandidatesDataContainer.visibility = GONE
        } else {
            mCandidatesMenuContainer.visibility = GONE
            mCandidatesDataContainer.visibility = VISIBLE
        }
    }

    // 刷新主题
    fun updateTheme(textColor: Int) {
        mRightArrowBtn.drawable.setTint(textColor)
        mMenuRightArrowBtn.drawable.setTint(textColor)
        mIvMenuSetting.drawable.setTint(textColor)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
        mFlowerType.setTextColor(textColor)
    }
}
