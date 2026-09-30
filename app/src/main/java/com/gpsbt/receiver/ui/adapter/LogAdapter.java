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

/** 接收日志列表。 */
public final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final List<LogBus.Entry> entries = new ArrayList<>();

    public void append(LogBus.Entry entry) {
        entries.add(entry);
        notifyItemInserted(entries.size() - 1);
    }

    public void reload(List<LogBus.Entry> value) {
        entries.clear();
        if (value != null) {
            entries.addAll(value);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        entries.clear();
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
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
        LogBus.Entry entry = entries.get(position);
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
        return entries.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView message;
        final int errorColor;
        final int warningColor;
        final int infoColor;
        final int dataColor;

        Holder(View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.tvLogTime);
            message = itemView.findViewById(R.id.tvLogMessage);
            errorColor = itemView.getContext().getColor(R.color.color_error);
            warningColor = itemView.getContext().getColor(R.color.color_warning);
            infoColor = itemView.getContext().getColor(R.color.color_text_secondary);
            dataColor = itemView.getContext().getColor(R.color.color_primary);
        }
    }
}
