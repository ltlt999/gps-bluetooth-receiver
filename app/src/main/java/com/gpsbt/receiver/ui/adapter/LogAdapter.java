package com.gpsbt.receiver.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.receiver.R;
import com.gpsbt.receiver.state.LogBus;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 日志列表：支持按类别筛选（全部 / 普通 / 卫星 / 数据）。 */
public final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

    /** 日志筛选类别。 */
    public enum Filter { ALL, NORMAL, SATELLITE, DATA }

    private static final int MAX_ENTRIES = 500;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final List<LogBus.Entry> all = new ArrayList<>();
    private final List<LogBus.Entry> visible = new ArrayList<>();
    private Filter filter = Filter.ALL;

    public void setFilter(Filter value) {
        filter = value == null ? Filter.ALL : value;
        rebuild();
    }

    public void append(LogBus.Entry entry) {
        if (all.size() >= MAX_ENTRIES) {
            all.remove(0);
            rebuild();
        } else {
            all.add(entry);
        }
        if (accepts(entry)) {
            visible.add(entry);
            notifyItemInserted(visible.size() - 1);
        }
    }

    public void reload(List<LogBus.Entry> value) {
        all.clear();
        if (value != null) {
            all.addAll(value);
        }
        rebuild();
    }

    public void clear() {
        all.clear();
        visible.clear();
        notifyDataSetChanged();
    }

    public int count() {
        return visible.size();
    }

    private void rebuild() {
        visible.clear();
        for (LogBus.Entry entry : all) {
            if (accepts(entry)) {
                visible.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    private boolean accepts(LogBus.Entry entry) {
        switch (filter) {
            case NORMAL:
                return entry.level == LogBus.Level.INFO
                        || entry.level == LogBus.Level.WARN
                        || entry.level == LogBus.Level.ERROR;
            case SATELLITE:
                return entry.level == LogBus.Level.SAT;
            case DATA:
                return entry.level == LogBus.Level.DATA;
            default:
                return true;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LogBus.Entry entry = visible.get(position);
        holder.time.setText(TIME_FORMAT.format(new Date(entry.time)));
        holder.message.setText(entry.message);
        int color;
        switch (entry.level) {
            case ERROR:
                color = holder.errorColor;
                break;
            case WARN:
                color = holder.warningColor;
                break;
            case SAT:
                color = holder.satelliteColor;
                break;
            case DATA:
                color = holder.dataColor;
                break;
            default:
                color = holder.infoColor;
                break;
        }
        holder.message.setTextColor(color);
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView message;
        final int errorColor;
        final int warningColor;
        final int infoColor;
        final int dataColor;
        final int satelliteColor;

        Holder(View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.tvLogTime);
            message = itemView.findViewById(R.id.tvLogMessage);
            errorColor = itemView.getContext().getColor(R.color.color_error);
            warningColor = itemView.getContext().getColor(R.color.color_warning);
            infoColor = itemView.getContext().getColor(R.color.color_text_secondary);
            dataColor = itemView.getContext().getColor(R.color.color_primary);
            satelliteColor = itemView.getContext().getColor(R.color.color_accent_blue);
        }
    }
}
