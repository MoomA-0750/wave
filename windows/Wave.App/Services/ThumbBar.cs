using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Wave.App.Services;

/// <summary>
/// タスクバーのアイコンにホバーしたときのプレビューの下に、前へ・再生/一時停止・次へを並べる（ITaskbarList3 のサムネイルのボタン）。
/// 押したらメディアキーと同じ PlayerService の操作に入る（ほかの端末を映している間はその端末への命令になる）。
/// ボタンはタスクバーのボタンができた知らせ（TaskbarButtonCreated）を受けてから足す。エクスプローラーが起動し直したときも同じ知らせが来るので足し直す。
/// </summary>
public sealed class ThumbBar
{
    private const uint PreviousId = 1, PlayId = 2, NextId = 3;
    private const uint WmCommand = 0x0111, WmSettingChange = 0x001A, WmDpiChanged = 0x02E0, ThbnClicked = 0x1800;

    private readonly IntPtr hwnd;
    private readonly PlayerService player;
    private readonly uint buttonCreated = RegisterWindowMessage("TaskbarButtonCreated");
    // 窓の手続きは GC に回収されないよう持っておく
    private readonly SubclassProc proc;
    private ITaskbarList3? taskbar;
    private bool added;
    private IntPtr previousIcon, playIcon, pauseIcon, nextIcon;

    public ThumbBar(Microsoft.UI.Xaml.Window window, PlayerService player)
    {
        hwnd = WinRT.Interop.WindowNative.GetWindowHandle(window);
        this.player = player;
        proc = WndProc;
        SetWindowSubclass(hwnd, proc, 0x57415645, IntPtr.Zero);
        player.PropertyChanged += OnPlayerChanged;
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerService.IsPlaying) or nameof(PlayerService.Current)) Update();
    }

    private IntPtr WndProc(IntPtr h, uint msg, IntPtr wParam, IntPtr lParam, IntPtr id, IntPtr data)
    {
        if (msg == buttonCreated) Add();
        else if (msg == WmCommand && ((long)wParam >> 16 & 0xFFFF) == ThbnClicked) Clicked((uint)((long)wParam & 0xFFFF));
        // 明るさ（タスクバーの色）・拡大率が変わったら絵柄を作り直す
        else if ((msg == WmSettingChange && lParam != IntPtr.Zero && Marshal.PtrToStringUni(lParam) == "ImmersiveColorSet") || msg == WmDpiChanged) Redraw();
        return DefSubclassProc(h, msg, wParam, lParam);
    }

    private void Clicked(uint id)
    {
        AppPaths.Log($"thumbbar click={id}");
        switch (id)
        {
            case PreviousId: player.Previous(); break;
            case PlayId: player.TogglePlay(); break;
            case NextId: player.Next(); break;
        }
    }

    private void Add()
    {
        try
        {
            taskbar = (ITaskbarList3)new CTaskbarList();
            taskbar.HrInit();
            MakeIcons();
            var buttons = Buttons();
            taskbar.ThumbBarAddButtons(hwnd, (uint)buttons.Length, buttons);
            added = true;
            AppPaths.Log("thumbbar added");
        }
        catch (Exception e)
        {
            added = false;
            AppPaths.Log($"thumbbar add failed {e.GetType().Name} 0x{e.HResult:X8}");
        }
    }

    private void Update()
    {
        if (!added || taskbar is null) return;
        var buttons = Buttons();
        try { taskbar.ThumbBarUpdateButtons(hwnd, (uint)buttons.Length, buttons); }
        catch (Exception e) { AppPaths.Log($"thumbbar update failed 0x{e.HResult:X8}"); }
    }

    private void Redraw()
    {
        if (!added) return;
        var old = new[] { previousIcon, playIcon, pauseIcon, nextIcon };
        MakeIcons();
        Update();
        foreach (var icon in old) if (icon != IntPtr.Zero) DestroyIcon(icon);
    }

    private ThumbButton[] Buttons()
    {
        var flags = player.Current is null ? ThumbButtonFlags.Disabled : ThumbButtonFlags.Enabled;
        return
        [
            Button(PreviousId, previousIcon, L.T("前へ", "Previous"), flags),
            player.IsPlaying ? Button(PlayId, pauseIcon, L.T("一時停止", "Pause"), flags) : Button(PlayId, playIcon, L.T("再生", "Play"), flags),
            Button(NextId, nextIcon, L.T("次へ", "Next"), flags),
        ];
    }

    private static ThumbButton Button(uint id, IntPtr icon, string tip, ThumbButtonFlags flags) =>
        new() { Mask = ThumbButtonMask.Icon | ThumbButtonMask.Tooltip | ThumbButtonMask.Flags, Id = id, Icon = icon, Tip = tip, Flags = flags };

    private void MakeIcons()
    {
        var size = GetSystemMetricsForDpi(49 /* SM_CXSMICON */, GetDpiForWindow(hwnd));
        // プレビューの帯はタスクバーの明るさ（Windows のモード）で描かれる。暗いときは白、明るいときは黒の絵柄にする
        var light = Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "SystemUsesLightTheme", 0) is int v && v != 0;
        var color = light ? 0x000000u : 0xFFFFFFu;
        previousIcon = GlyphIcon('', size, color);
        playIcon = GlyphIcon('', size, color);
        pauseIcon = GlyphIcon('', size, color);
        nextIcon = GlyphIcon('', size, color);
    }

    /// <summary>Segoe Fluent Icons（無ければ Segoe MDL2 Assets）の字を、透明の背景のアイコンにする。</summary>
    private static IntPtr GlyphIcon(char glyph, int size, uint rgb)
    {
        var info = new BitmapInfoHeader { Size = Marshal.SizeOf<BitmapInfoHeader>(), Width = size, Height = -size, Planes = 1, BitCount = 32 };
        var screen = GetDC(IntPtr.Zero);
        var dc = CreateCompatibleDC(screen);
        ReleaseDC(IntPtr.Zero, screen);
        var color = CreateDIBSection(dc, ref info, 0, out var bits, IntPtr.Zero, 0);
        var oldBitmap = SelectObject(dc, color);
        // 黒の上に白で描き、明るさを不透明さにする（GDI は透明を描けないため）
        var font = CreateFont(-size, 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 4 /* ANTIALIASED_QUALITY */, 0, FontFamily());
        var oldFont = SelectObject(dc, font);
        SetBkMode(dc, 1);
        SetTextColor(dc, 0xFFFFFF);
        var rect = new Rect { Right = size, Bottom = size };
        DrawText(dc, glyph.ToString(), 1, ref rect, 0x1 | 0x4 | 0x20 | 0x100 /* CENTER | VCENTER | SINGLELINE | NOCLIP */);
        GdiFlush();
        var pixels = new int[size * size];
        Marshal.Copy(bits, pixels, 0, pixels.Length);
        for (var i = 0; i < pixels.Length; i++)
        {
            var alpha = (uint)pixels[i] & 0xFF;
            pixels[i] = (int)((alpha << 24) | rgb);
        }
        Marshal.Copy(pixels, 0, bits, pixels.Length);
        SelectObject(dc, oldFont);
        DeleteObject(font);
        SelectObject(dc, oldBitmap);
        DeleteDC(dc);
        var mask = CreateBitmap(size, size, 1, 1, IntPtr.Zero);
        var iconInfo = new IconInfo { IsIcon = true, Mask = mask, Color = color };
        var icon = CreateIconIndirect(ref iconInfo);
        DeleteObject(mask);
        DeleteObject(color);
        return icon;
    }

    private static string? fontFamily;

    private static string FontFamily()
    {
        if (fontFamily is not null) return fontFamily;
        // Windows 10 には Segoe Fluent Icons が無い（同じ字の番号の Segoe MDL2 Assets を使う）
        var fluent = File.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Fonts), "SegoeIcons.ttf"));
        return fontFamily = fluent ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";
    }

    // ---- Win32・COM ----

    [Flags]
    private enum ThumbButtonMask : uint { Bitmap = 1, Icon = 2, Tooltip = 4, Flags = 8 }

    [Flags]
    private enum ThumbButtonFlags : uint { Enabled = 0, Disabled = 1 }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ThumbButton
    {
        public ThumbButtonMask Mask;
        public uint Id;
        public uint Bitmap;
        public IntPtr Icon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string Tip;
        public ThumbButtonFlags Flags;
    }

    [ComImport, Guid("56FDF344-FD6D-11d0-958A-006097C9A090")]
    private class CTaskbarList;

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf")]
    private interface ITaskbarList3
    {
        void HrInit();
        void AddTab(IntPtr hwnd);
        void DeleteTab(IntPtr hwnd);
        void ActivateTab(IntPtr hwnd);
        void SetActiveAlt(IntPtr hwnd);
        void MarkFullscreenWindow(IntPtr hwnd, [MarshalAs(UnmanagedType.Bool)] bool fullscreen);
        void SetProgressValue(IntPtr hwnd, ulong completed, ulong total);
        void SetProgressState(IntPtr hwnd, int flags);
        void RegisterTab(IntPtr tab, IntPtr mdi);
        void UnregisterTab(IntPtr tab);
        void SetTabOrder(IntPtr tab, IntPtr insertBefore);
        void SetTabActive(IntPtr tab, IntPtr mdi, uint reserved);
        void ThumbBarAddButtons(IntPtr hwnd, uint count, [MarshalAs(UnmanagedType.LPArray)] ThumbButton[] buttons);
        void ThumbBarUpdateButtons(IntPtr hwnd, uint count, [MarshalAs(UnmanagedType.LPArray)] ThumbButton[] buttons);
    }

    private delegate IntPtr SubclassProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam, IntPtr id, IntPtr data);

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfoHeader
    {
        public int Size, Width, Height;
        public short Planes, BitCount;
        public int Compression, SizeImage, XPelsPerMeter, YPelsPerMeter, ClrUsed, ClrImportant;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IconInfo
    {
        [MarshalAs(UnmanagedType.Bool)] public bool IsIcon;
        public int HotspotX, HotspotY;
        public IntPtr Mask, Color;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect { public int Left, Top, Right, Bottom; }

    [DllImport("comctl32.dll")] private static extern bool SetWindowSubclass(IntPtr hwnd, SubclassProc proc, nuint id, IntPtr data);
    [DllImport("comctl32.dll")] private static extern IntPtr DefSubclassProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern uint RegisterWindowMessage(string name);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hwnd, IntPtr dc);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int DrawText(IntPtr dc, string text, int count, ref Rect rect, uint format);
    [DllImport("user32.dll")] private static extern IntPtr CreateIconIndirect(ref IconInfo info);
    [DllImport("user32.dll")] private static extern bool DestroyIcon(IntPtr icon);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr dc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateDIBSection(IntPtr dc, ref BitmapInfoHeader info, uint usage, out IntPtr bits, IntPtr section, uint offset);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateBitmap(int width, int height, uint planes, uint bitCount, IntPtr bits);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr dc, IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr obj);
    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateFont(int height, int width, int escapement, int orientation, int weight, uint italic, uint underline, uint strikeOut,
        uint charSet, uint outPrecision, uint clipPrecision, uint quality, uint pitchAndFamily, string face);
    [DllImport("gdi32.dll")] private static extern int SetBkMode(IntPtr dc, int mode);
    [DllImport("gdi32.dll")] private static extern uint SetTextColor(IntPtr dc, uint color);
    [DllImport("gdi32.dll")] private static extern bool GdiFlush();
}
