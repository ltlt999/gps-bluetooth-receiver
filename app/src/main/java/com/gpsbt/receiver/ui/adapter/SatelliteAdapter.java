package com.gpsbt.receiver.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gpsbt.receiver.R;
import com.gpsbt.receiver.nmea.NmeaParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 卫星明细列表：编号、星座（国旗）、信噪比、仰角、方位角。 */
public final class SatelliteAdapter extends RecyclerView.Adapter<SatelliteAdapter.Holder> {

    private static final String[] CONST_ORDER = {"GPS", "BDS", "GLN", "GAL", "QZSS", "SBAS", "NavIC"};

    private final List<NmeaParser.Sat> items = new ArrayList<>();

    public void submit(List<NmeaParser.Sat> sats) {
        items.clear();
        if (sats != null) {
            items.addAll(sats);
        }
        Collections.sort(items, new Comparator<NmeaParser.Sat>() {
            @Override
            public int compare(NmeaParser.Sat left, NmeaParser.Sat right) {
                int leftOrder = orderOf(left.constellation);
                int rightOrder = orderOf(right.constellation);
                if (leftOrder != rightOrder) {
                    return leftOrder - rightOrder;
                }
                return left.prn - right.prn;
            }
        });
        notifyDataSetChanged();
    }

    private static int orderOf(String constellation) {
        if (constellation == null) {
            return CONST_ORDER.length;
        }
        for (int i = 0; i < CONST_ORDER.length; i++) {
            if (CONST_ORDER[i].equals(constellation)) {
                return i;
            }
        }
        return CONST_ORDER.length;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_satellite, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        NmeaParser.Sat sat = items.get(position);
        holder.prn.setText(sat.used
                ? String.format(Locale.US, "%02d ●", sat.prn)
                : String.format(Locale.US, "%02d", sat.prn));

        String constellation = sat.constellation;
        if (constellation == null) {
            holder.flag.setImageResource(R.drawable.flag_unknown);
            holder.constLabel.setText("—");
        } else {
            holder.flag.setImageResource(flagOf(constellation));
            holder.constLabel.setText(constellation);
        }

        holder.snr.setText(Float.isNaN(sat.snr) ? "--" : String.format(Locale.US, "%d", (int) sat.snr));
        holder.elev.setText(Float.isNaN(sat.elevationDeg) ? "--"
                : String.format(Locale.US, "%.0f°", sat.elevationDeg));
        holder.azim.setText(Float.isNaN(sat.azimuthDeg) ? "--"
                : String.format(Locale.US, "%.0f°", sat.azimuthDeg));

        // 参与定位的卫星高亮
        int color = holder.itemView.getContext().getColor(sat.used
                ? R.color.color_primary : R.color.color_text_primary);
        holder.prn.setTextColor(color);
        holder.snr.setTextColor(color);
    }

    private static int flagOf(String constellation) {
        switch (constellation) {
            case "GPS":
                return R.drawable.flag_gps;
            case "BDS":
                return R.drawable.flag_bds;
            case "GLN":
                return R.drawable.flag_gln;
            case "GAL":
                return R.drawable.flag_gal;
            case "QZSS":
                return R.drawable.flag_qzss;
            case "NavIC":
                return R.drawable.flag_navic;
            default:
                return R.drawable.flag_unknown;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView prn;
        final ImageView flag;
        final TextView constLabel;
        final TextView snr;
        final TextView elev;
        final TextView azim;

        Holder(View itemView) {
            super(itemView);
            prn = itemView.findViewById(R.id.tvSatPrn);
            flag = itemView.findViewById(R.id.ivSatFlag);
            constLabel = itemView.findViewById(R.id.tvSatConst);
            snr = itemView.findViewById(R.id.tvSatSnr);
            elev = itemView.findViewById(R.id.tvSatElev);
            azim = itemView.findViewById(R.id.tvSatAzim);
        }
    }
}
