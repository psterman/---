// Generated by view binder compiler. Do not edit!
package com.example.aifloatingball.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import com.example.aifloatingball.R;
import java.lang.NullPointerException;
import java.lang.Override;
import java.lang.String;

public final class CardAiEngineBinding implements ViewBinding {
  @NonNull
  private final CardView rootView;

  @NonNull
  public final FrameLayout contentArea;

  @NonNull
  public final ImageView engineIcon;

  @NonNull
  public final TextView engineName;

  @NonNull
  public final Button pasteButton;

  @NonNull
  public final LinearLayout titleBar;

  @NonNull
  public final WebView webView;

  private CardAiEngineBinding(@NonNull CardView rootView, @NonNull FrameLayout contentArea,
      @NonNull ImageView engineIcon, @NonNull TextView engineName, @NonNull Button pasteButton,
      @NonNull LinearLayout titleBar, @NonNull WebView webView) {
    this.rootView = rootView;
    this.contentArea = contentArea;
    this.engineIcon = engineIcon;
    this.engineName = engineName;
    this.pasteButton = pasteButton;
    this.titleBar = titleBar;
    this.webView = webView;
  }

  @Override
  @NonNull
  public CardView getRoot() {
    return rootView;
  }

  @NonNull
  public static CardAiEngineBinding inflate(@NonNull LayoutInflater inflater) {
    return inflate(inflater, null, false);
  }

  @NonNull
  public static CardAiEngineBinding inflate(@NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent, boolean attachToParent) {
    View root = inflater.inflate(R.layout.card_ai_engine, parent, false);
    if (attachToParent) {
      parent.addView(root);
    }
    return bind(root);
  }

  @NonNull
  public static CardAiEngineBinding bind(@NonNull View rootView) {
    // The body of this method is generated in a way you would not otherwise write.
    // This is done to optimize the compiled bytecode for size and performance.
    int id;
    missingId: {
      id = R.id.content_area;
      FrameLayout contentArea = ViewBindings.findChildViewById(rootView, id);
      if (contentArea == null) {
        break missingId;
      }

      id = R.id.engine_icon;
      ImageView engineIcon = ViewBindings.findChildViewById(rootView, id);
      if (engineIcon == null) {
        break missingId;
      }

      id = R.id.engine_name;
      TextView engineName = ViewBindings.findChildViewById(rootView, id);
      if (engineName == null) {
        break missingId;
      }

      id = R.id.paste_button;
      Button pasteButton = ViewBindings.findChildViewById(rootView, id);
      if (pasteButton == null) {
        break missingId;
      }

      id = R.id.title_bar;
      LinearLayout titleBar = ViewBindings.findChildViewById(rootView, id);
      if (titleBar == null) {
        break missingId;
      }

      id = R.id.web_view;
      WebView webView = ViewBindings.findChildViewById(rootView, id);
      if (webView == null) {
        break missingId;
      }

      return new CardAiEngineBinding((CardView) rootView, contentArea, engineIcon, engineName,
          pasteButton, titleBar, webView);
    }
    String missingId = rootView.getResources().getResourceName(id);
    throw new NullPointerException("Missing required view with ID: ".concat(missingId));
  }
}
