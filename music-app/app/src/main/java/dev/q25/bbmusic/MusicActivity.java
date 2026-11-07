package dev.q25.bbmusic;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;

/**
 * Standalone BB Music UI host.
 *
 * The native ListView is configured like the BB UI Quick Settings ListView:
 * one owner for touch scrolling, directional-key selection, and row recycling.
 * No visual rows, headers, transport controls, or music menus are defined yet.
 *
 * Music-source connections are intentionally not started by this Activity.
 * SymfoniumSource remains an optional adapter, not a startup dependency.
 */
public final class MusicActivity extends Activity {
    private ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        list = new ListView(this);
        list.setBackgroundColor(Color.BLACK);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(false);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setSelector(android.R.color.transparent);
        list.setDivider(new ColorDrawable(0xff454a4d));
        list.setDividerHeight(dp(1));

        // An intentionally empty BaseAdapter. We will build row presentation
        // and actions only as the BBOS6 UI is specified.
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return 0; }
            @Override public Object getItem(int position) { return null; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return convertView == null ? new View(MusicActivity.this) : convertView;
            }
        });
        setContentView(list);
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
