package com.mapbox.navigation.qa.view

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mapbox.navigation.qa.R
import com.mapbox.navigation.qa.view.adapters.CategoryListAdapterSupport.categoryListOnBindViewHolderFun
import com.mapbox.navigation.qa.view.adapters.CategoryListAdapterSupport.itemTypeProviderFun
import com.mapbox.navigation.qa.view.adapters.CategoryListAdapterSupport.viewHolderFactory
import com.mapbox.navigation.qa.view.adapters.GenericListAdapter
import com.mapbox.navigation.qa.view.adapters.GenericListAdapterItemSelectedFun
import com.mapbox.navigation.qa.view.adapters.GenericListAdapterSameItemFun
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val layoutManager = LinearLayoutManager(this)
        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = GenericListAdapter(
            categoryListOnBindViewHolderFun,
            viewHolderFactory,
            categorySelectedDelegate,
            null,
            categorySameItemFun,
            categorySameItemFun,
            itemTypeProviderFun
        )
        categoryList.addItemDecoration(DividerItemDecoration(this, layoutManager.orientation))
    }

    override fun onStart() {
        super.onStart()
        val categories = resources.getStringArray(R.array.categories).toList()
        (categoryList.adapter as GenericListAdapter<String, *>).let {
            it.swap(categories)
        }
    }

    private val categorySelectedDelegate: GenericListAdapterItemSelectedFun<String> = { postionAndValue ->
        // todo do something when item clicked
    }

    private val categorySameItemFun: GenericListAdapterSameItemFun<String> = { item1, item2 ->
        item1 == item2
    }
}
