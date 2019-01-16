package org.dolphinemu.dolphinemu.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import org.dolphinemu.dolphinemu.NativeLibrary;
import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.dialogs.RunningSettingDialog;
import org.dolphinemu.dolphinemu.dialogs.StateSavesDialog;
import org.dolphinemu.dolphinemu.fragments.EmulationFragment;
import org.dolphinemu.dolphinemu.model.GameFile;
import org.dolphinemu.dolphinemu.overlay.InputOverlay;
import org.dolphinemu.dolphinemu.services.GameFileCacheService;
import org.dolphinemu.dolphinemu.ui.main.MainActivity;
import org.dolphinemu.dolphinemu.ui.main.MainPresenter;
import org.dolphinemu.dolphinemu.ui.platform.Platform;
import org.dolphinemu.dolphinemu.utils.ControllerMappingHelper;
import org.dolphinemu.dolphinemu.utils.FileBrowserHelper;
import org.dolphinemu.dolphinemu.utils.Java_GCAdapter;
import org.dolphinemu.dolphinemu.utils.Java_WiimoteAdapter;
import org.dolphinemu.dolphinemu.utils.Rumble;

import java.io.File;
import java.util.List;

public final class EmulationActivity extends AppCompatActivity
{
  public static final int REQUEST_CHANGE_DISC = 1;

  private SensorManager mSensorManager;
  private View mDecorView;
  private EmulationFragment mEmulationFragment;

  private SharedPreferences mPreferences;
  private ControllerMappingHelper mControllerMappingHelper;

  private boolean mStopEmulation;
  private boolean mMenuVisible;

  private static boolean sIsGameCubeGame;
  private static GameFile sGameFile;

  private boolean activityRecreated;
  private String mSelectedTitle;
  private int mPlatform;
  private String[] mPaths;
  private String mSavedState;

  public static final String RUMBLE_PREF_KEY = "PhoneRumble";
  public static final String EXTRA_SELECTED_GAMES = "SelectedGames";
  public static final String EXTRA_SELECTED_TITLE = "SelectedTitle";
  public static final String EXTRA_PLATFORM = "Platform";
  public static final String EXTRA_SAVED_STATE = "SavedState";

  public static void launch(FragmentActivity activity, GameFile gameFile, String savedState)
  {
    Intent launcher = new Intent(activity, EmulationActivity.class);

    launcher.putExtra(EXTRA_SELECTED_GAMES, GameFileCacheService.getAllDiscPaths(gameFile));
    launcher.putExtra(EXTRA_SELECTED_TITLE, gameFile.getTitle());
    launcher.putExtra(EXTRA_PLATFORM, gameFile.getPlatform());
    launcher.putExtra(EXTRA_SAVED_STATE, savedState);
    Bundle options = new Bundle();

    //
    sGameFile = gameFile;

    // I believe this warning is a bug. Activities are FragmentActivity from the support lib
    //noinspection RestrictedApi
    activity.startActivityForResult(launcher, MainPresenter.REQUEST_EMULATE_GAME, options);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);

    if (savedInstanceState == null)
    {
      // Get params we were passed
      Intent gameToEmulate = getIntent();
      mPaths = gameToEmulate.getStringArrayExtra(EXTRA_SELECTED_GAMES);
      mSelectedTitle = gameToEmulate.getStringExtra(EXTRA_SELECTED_TITLE);
      mPlatform = gameToEmulate.getIntExtra(EXTRA_PLATFORM, 0);
      mSavedState = gameToEmulate.getStringExtra(EXTRA_SAVED_STATE);
      activityRecreated = false;
    }
    else
    {
      activityRecreated = true;
      restoreState(savedInstanceState);
    }

    // TODO: The accurate way to find out which console we're emulating is to
    // first launch emulation and then ask the core which console we're emulating
    sIsGameCubeGame = Platform.fromNativeInt(mPlatform) == Platform.GAMECUBE;
    mControllerMappingHelper = new ControllerMappingHelper();

    // Get a handle to the Window containing the UI.
    mDecorView = getWindow().getDecorView();
    mDecorView.setOnSystemUiVisibilityChangeListener(visibility ->
    {
      if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0)
      {
        // Go back to immersive fullscreen mode in 3s
        Handler handler = new Handler(getMainLooper());
        handler.postDelayed(this::enableFullscreenImmersive, 3000 /* 3s */);
      }
    });
    // Set these options now so that the SurfaceView the game renders into is the right size.
    mStopEmulation = false;
    enableFullscreenImmersive();

    setTheme(R.style.DolphinEmulationBase);

    Java_GCAdapter.manager = (UsbManager) getSystemService(Context.USB_SERVICE);
    Java_WiimoteAdapter.manager = (UsbManager) getSystemService(Context.USB_SERVICE);
    Rumble.initDeviceRumble();

    setContentView(R.layout.activity_emulation);

    // Find or create the EmulationFragment
    mEmulationFragment = (EmulationFragment) getSupportFragmentManager()
      .findFragmentById(R.id.frame_emulation_fragment);
    if (mEmulationFragment == null)
    {
      mEmulationFragment = EmulationFragment.newInstance(mPaths);
      getSupportFragmentManager().beginTransaction()
        .add(R.id.frame_emulation_fragment, mEmulationFragment)
        .commit();
    }

    setTitle(mSelectedTitle);

    mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    Rumble.setPhoneRumble(this, mPreferences.getBoolean(RUMBLE_PREF_KEY, true));
  }

  @Override
  protected void onSaveInstanceState(Bundle outState)
  {
    if (!isChangingConfigurations())
    {
      saveTemporaryState();
    }
    outState.putStringArray(EXTRA_SELECTED_GAMES, mPaths);
    outState.putString(EXTRA_SELECTED_TITLE, mSelectedTitle);
    outState.putInt(EXTRA_PLATFORM, mPlatform);
    outState.putString(EXTRA_SAVED_STATE, mSavedState);
    super.onSaveInstanceState(outState);
  }

  protected void restoreState(Bundle savedInstanceState)
  {
    mPaths = savedInstanceState.getStringArray(EXTRA_SELECTED_GAMES);
    mSelectedTitle = savedInstanceState.getString(EXTRA_SELECTED_TITLE);
    mPlatform = savedInstanceState.getInt(EXTRA_PLATFORM);
    mSavedState = savedInstanceState.getString(EXTRA_SAVED_STATE);
  }

  @Override
  public void onBackPressed()
  {
    if (mMenuVisible)
    {
      mStopEmulation = true;
      mEmulationFragment.stopEmulation();
      finish();
    }
    else
    {
      disableFullscreenImmersive();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent result)
  {
    switch (requestCode)
    {
      case REQUEST_CHANGE_DISC:
        // If the user picked a file, as opposed to just backing out.
        if (resultCode == MainActivity.RESULT_OK)
        {
          String newDiscPath = FileBrowserHelper.getSelectedDirectory(result);
          if (!TextUtils.isEmpty(newDiscPath))
          {
            NativeLibrary.ChangeDisc(newDiscPath);
          }
        }
        break;
    }
  }

  private void enableFullscreenImmersive()
  {
    if (mStopEmulation)
    {
      return;
    }
    mMenuVisible = false;
    // It would be nice to use IMMERSIVE_STICKY, but that doesn't show the toolbar.
    mDecorView.setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_FULLSCREEN |
        View.SYSTEM_UI_FLAG_IMMERSIVE);
  }

  private void disableFullscreenImmersive()
  {
    mMenuVisible = true;
    mDecorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu)
  {
    // Inflate the menu; this adds items to the action bar if it is present.
    if (sIsGameCubeGame)
    {
      getMenuInflater().inflate(R.menu.menu_emulation, menu);
    }
    else
    {
      getMenuInflater().inflate(R.menu.menu_emulation_wii, menu);
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item)
  {
    switch (item.getItemId())
    {
      // Edit the placement of the controls
      case R.id.menu_emulation_edit_layout:
        editControlsPlacement();
        break;

      case R.id.menu_emulation_joystick_settings:
        showJoystickSettings();
        break;

      case R.id.menu_emulation_sensor_settings:
        showSensorSettings();
        break;

      // Enable/Disable specific buttons or the entire input overlay.
      case R.id.menu_emulation_toggle_controls:
        toggleControls();
        break;

      // Adjust the scale of the overlay controls.
      case R.id.menu_emulation_adjust_scale:
        adjustScale();
        break;

      // (Wii games only) Change the controller for the input overlay.
      case R.id.menu_emulation_choose_controller:
        chooseController();
        break;

      /*case R.id.menu_refresh_wiimotes:
        NativeLibrary.RefreshWiimotes();
        break;*/

      // Screenshot capturing
      case R.id.menu_emulation_screenshot:
        NativeLibrary.SaveScreenShot();
        break;

      // Quick save / load
      case R.id.menu_quicksave:
        showStateSaves();
        break;

      case R.id.menu_change_disc:
        FileBrowserHelper.openFilePicker(this, REQUEST_CHANGE_DISC);
        break;

      case R.id.menu_running_setting:
        RunningSettingDialog.newInstance()
          .show(getSupportFragmentManager(), "RunningSettingDialog");
        break;

      default:
        return false;
    }

    return true;
  }

  private void showStateSaves()
  {
    StateSavesDialog.newInstance(sGameFile.getGameId()).show(getSupportFragmentManager(), "StateSavesDialog");
  }

  private void showJoystickSettings()
  {
    final int joystick = InputOverlay.sJoyStickSetting;
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.emulation_joystick_settings);

    builder.setSingleChoiceItems(R.array.wiiJoystickSettings, joystick,
      (dialog, indexSelected) ->
      {
        InputOverlay.sJoyStickSetting = indexSelected;
      });
    builder.setOnDismissListener((dialogInterface) ->
    {
      if(InputOverlay.sJoyStickSetting != joystick)
      {
        mEmulationFragment.refreshInputOverlay();
      }
    });

    AlertDialog alertDialog = builder.create();
    alertDialog.show();
  }

  private void showSensorSettings()
  {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.emulation_sensor_settings);

    if(sIsGameCubeGame)
    {
      int sensor = InputOverlay.sSensorGCSetting;
      builder.setSingleChoiceItems(R.array.gcSensorSettings, sensor,
        (dialog, indexSelected) ->
        {
          InputOverlay.sSensorGCSetting = indexSelected;
        });
      builder.setOnDismissListener((dialogInterface) ->
      {
        setSensorState(InputOverlay.sSensorGCSetting > 0);
      });
    }
    else
    {
      int sensor = InputOverlay.sSensorWiiSetting;
      builder.setSingleChoiceItems(R.array.wiiSensorSettings, sensor,
        (dialog, indexSelected) ->
        {
          InputOverlay.sSensorWiiSetting = indexSelected;
        });
      builder.setOnDismissListener((dialogInterface) ->
      {
        setSensorState(InputOverlay.sSensorWiiSetting > 0);
      });
    }

    AlertDialog alertDialog = builder.create();
    alertDialog.show();
  }

  private void setSensorState(boolean enabled)
  {
    if(enabled)
    {
      if(mSensorManager == null)
      {
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        Sensor rotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if(rotationVector != null)
        {
          mSensorManager.registerListener(mEmulationFragment, rotationVector, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
      }
    }
    else
    {
      if(mSensorManager != null)
      {
        mSensorManager.unregisterListener(mEmulationFragment);
        mSensorManager = null;
      }
    }

    //
    mEmulationFragment.onAccuracyChanged(null, 0);
  }

  private void editControlsPlacement()
  {
    if (mEmulationFragment.isConfiguringControls())
    {
      mEmulationFragment.stopConfiguringControls();
    }
    else
    {
      mEmulationFragment.startConfiguringControls();
    }
  }

  @Override
  protected void onResume()
  {
    super.onResume();

    if(mSensorManager != null)
    {
      Sensor rotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
      if(rotationVector != null)
      {
        mSensorManager.registerListener(mEmulationFragment, rotationVector, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
      }
    }
  }

  @Override
  protected void onPause()
  {
    super.onPause();

    final SharedPreferences.Editor editor = mPreferences.edit();
    editor.putInt(InputOverlay.JOYSTICK_PREF_KEY, InputOverlay.sJoyStickSetting);
    editor.putInt(InputOverlay.CONTROL_TYPE_PREF_KEY, InputOverlay.sControllerType);
    editor.putInt(InputOverlay.CONTROL_SCALE_PREF_KEY, InputOverlay.sControllerScale);
    editor.apply();

    if(mSensorManager != null)
    {
      mSensorManager.unregisterListener(mEmulationFragment);
    }
  }

  // Gets button presses
  @Override
  public boolean dispatchKeyEvent(KeyEvent event)
  {
    if (mMenuVisible)
    {
      return super.dispatchKeyEvent(event);
    }

    int action;
    switch (event.getAction())
    {
      case KeyEvent.ACTION_DOWN:
        // Handling the case where the back button is pressed.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK)
        {
          onBackPressed();
          return true;
        }
        // Normal key events.
        action = NativeLibrary.ButtonState.PRESSED;
        break;
      case KeyEvent.ACTION_UP:
        action = NativeLibrary.ButtonState.RELEASED;
        break;
      default:
        return false;
    }

    InputDevice input = event.getDevice();
    if (input != null)
      return NativeLibrary.onGamePadEvent(input.getDescriptor(), event.getKeyCode(), action);
    else
      return false;
  }

  private void toggleControls()
  {
    final SharedPreferences.Editor editor = mPreferences.edit();
    boolean[] enabledButtons = new boolean[16];
    int controller = InputOverlay.sControllerType;
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.emulation_toggle_controls);

    if (sIsGameCubeGame || controller == InputOverlay.CONTROLLER_GAMECUBE)
    {
      for (int i = 0; i < enabledButtons.length; i++)
      {
        enabledButtons[i] = mPreferences.getBoolean("buttonToggleGc" + i, true);
      }
      builder.setMultiChoiceItems(R.array.gcpadButtons, enabledButtons,
        (dialog, indexSelected, isChecked) -> editor
          .putBoolean("buttonToggleGc" + indexSelected, isChecked));
    }
    else if (controller == InputOverlay.COCONTROLLER_CLASSIC)
    {
      for (int i = 0; i < enabledButtons.length; i++)
      {
        enabledButtons[i] = mPreferences.getBoolean("buttonToggleClassic" + i, true);
      }
      builder.setMultiChoiceItems(R.array.classicButtons, enabledButtons,
        (dialog, indexSelected, isChecked) -> editor
          .putBoolean("buttonToggleClassic" + indexSelected, isChecked));
    }
    else
    {
      for (int i = 0; i < enabledButtons.length; i++)
      {
        enabledButtons[i] = mPreferences.getBoolean("buttonToggleWii" + i, true);
      }
      if (controller == InputOverlay.CONTROLLER_WIINUNCHUK)
      {
        builder.setMultiChoiceItems(R.array.nunchukButtons, enabledButtons,
          (dialog, indexSelected, isChecked) -> editor
            .putBoolean("buttonToggleWii" + indexSelected, isChecked));
      }
      else
      {
        builder.setMultiChoiceItems(R.array.wiimoteButtons, enabledButtons,
          (dialog, indexSelected, isChecked) -> editor
            .putBoolean("buttonToggleWii" + indexSelected, isChecked));
      }
    }

    builder.setNeutralButton(getString(R.string.emulation_toggle_all),
      (dialogInterface, i) -> mEmulationFragment.toggleInputOverlayVisibility());
    builder.setPositiveButton(getString(R.string.ok), (dialogInterface, i) ->
    {
      editor.apply();
      mEmulationFragment.refreshInputOverlay();
    });

    AlertDialog alertDialog = builder.create();
    alertDialog.show();
  }

  private void adjustScale()
  {
    LayoutInflater inflater = LayoutInflater.from(this);
    View view = inflater.inflate(R.layout.dialog_seekbar, null);

    final SeekBar seekbar = view.findViewById(R.id.seekbar);
    final TextView value = view.findViewById(R.id.text_value);
    final TextView units = view.findViewById(R.id.text_units);

    seekbar.setMax(150);
    seekbar.setProgress(InputOverlay.sControllerScale);
    seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
    {
      public void onStartTrackingTouch(SeekBar seekBar)
      {
        // Do nothing
      }

      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
      {
        value.setText(String.valueOf(progress + 50));
      }

      public void onStopTrackingTouch(SeekBar seekBar)
      {
        // Do nothing
      }
    });

    value.setText(String.valueOf(seekbar.getProgress() + 50));
    units.setText("%");

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.emulation_control_scale);
    builder.setView(view);
    builder.setPositiveButton(getString(R.string.ok), (dialogInterface, i) ->
    {
      InputOverlay.sControllerScale = seekbar.getProgress();
      mEmulationFragment.refreshInputOverlay();
    });

    AlertDialog alertDialog = builder.create();
    alertDialog.show();
  }

  private void chooseController()
  {
    int controller = InputOverlay.sControllerType;
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.emulation_choose_controller);
    builder.setSingleChoiceItems(R.array.controllersEntries, controller,
      (dialog, indexSelected) ->
      {
        InputOverlay.sControllerType = indexSelected;
      });
    builder.setOnDismissListener((dialogInterface) ->
    {
      NativeLibrary.SetConfig("WiimoteNew.ini", "Wiimote1", "Extension",
        getResources().getStringArray(R.array.controllersValues)[InputOverlay.sControllerType]);
      mEmulationFragment.refreshInputOverlay();
    });

    AlertDialog alertDialog = builder.create();
    alertDialog.show();
  }

  @Override
  public boolean dispatchGenericMotionEvent(MotionEvent event)
  {
    if (mMenuVisible)
    {
      return false;
    }

    if (((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0))
    {
      return super.dispatchGenericMotionEvent(event);
    }

    // Don't attempt to do anything if we are disconnecting a device.
    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL)
      return true;

    InputDevice input = event.getDevice();
    List<InputDevice.MotionRange> motions = input.getMotionRanges();

    for (InputDevice.MotionRange range : motions)
    {
      int axis = range.getAxis();
      float origValue = event.getAxisValue(axis);
      float value = mControllerMappingHelper.scaleAxis(input, axis, origValue);
      // If the input is still in the "flat" area, that means it's really zero.
      // This is used to compensate for imprecision in joysticks.
      if (Math.abs(value) > range.getFlat())
      {
        NativeLibrary.onGamePadMoveEvent(input.getDescriptor(), axis, value);
      }
      else
      {
        NativeLibrary.onGamePadMoveEvent(input.getDescriptor(), axis, 0.0f);
      }
    }

    return true;
  }

  public static boolean isGameCubeGame()
  {
    return sIsGameCubeGame;
  }

  public boolean isActivityRecreated()
  {
    return activityRecreated;
  }

  public String getSavedState()
  {
    return mSavedState;
  }

  public void saveTemporaryState()
  {
    mSavedState = getFilesDir() + File.separator + "temp.sav";
    NativeLibrary.SaveStateAs(mSavedState, true);
  }

  public void setTouchPointerEnabled(boolean enabled)
  {
    mEmulationFragment.setTouchPointerEnabled(enabled);
  }
}
