using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace Wave.App.Services;

/// <summary>
/// Windows の通知（トースト）。パッケージにしていない自己完結の作りでは AppNotificationManager が使えない（登録が 0x8007007E で失敗する）ので、
/// 昔からの ToastNotificationManager を使う。それにはアプリの ID（AUMID）を持つスタートメニューのショートカットが要るので、無ければ作る。
/// 押されたときの知らせ（Activated）は、アプリが動いている間だけ届く。
/// </summary>
public static class Toasts
{
    private const string Aumid = "xyz.mooma0750.wave";

    private static ToastNotifier? notifier;

    /// <summary>押された通知の引数（launch の文字列）。</summary>
    public static event Action<string>? Activated;

    public static void Initialize()
    {
        try
        {
            SetCurrentProcessExplicitAppUserModelID(Aumid);
            EnsureShortcut();
            notifier = ToastNotificationManager.CreateToastNotifier(Aumid);
        }
        catch (Exception e)
        {
            AppPaths.Log($"toast init failed type={e.GetType().Name} hr=0x{e.HResult:X8}");
        }
    }

    /// <summary>題・本文・画像の通知を出す。launch は押されたときに Activated へ渡す文字列。</summary>
    public static void Show(string title, string body, string launch, string? imageUrl = null)
    {
        if (notifier is null) return;
        try
        {
            var image = imageUrl is null ? "" : $"<image placement=\"appLogoOverride\" src=\"{Escape(imageUrl)}\" />";
            var xml = new XmlDocument();
            xml.LoadXml($"<toast launch=\"{Escape(launch)}\"><visual><binding template=\"ToastGeneric\"><text>{Escape(title)}</text><text>{Escape(body)}</text>{image}</binding></visual></toast>");
            var toast = new ToastNotification(xml);
            toast.Activated += (_, args) =>
            {
                if (args is ToastActivatedEventArgs a) Activated?.Invoke(a.Arguments);
            };
            notifier.Show(toast);
        }
        catch (Exception e)
        {
            AppPaths.Log($"toast failed type={e.GetType().Name} hr=0x{e.HResult:X8}");
        }
    }

    private static string Escape(string text) => System.Security.SecurityElement.Escape(text) ?? "";

    /// <summary>スタートメニューに、アプリの ID 付きのショートカット（wave.lnk）を置く。今の exe を指していればそのまま。</summary>
    private static void EnsureShortcut()
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("no process path");
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "wave.lnk");
        var link = (IShellLinkW)new CShellLink();
        if (File.Exists(path))
        {
            ((IPersistFile)link).Load(path, 0);
            var target = new System.Text.StringBuilder(260);
            link.GetPath(target, target.Capacity, IntPtr.Zero, 0);
            if (string.Equals(target.ToString(), exe, StringComparison.OrdinalIgnoreCase)) return;
            link = (IShellLinkW)new CShellLink();
        }
        link.SetPath(exe);
        link.SetWorkingDirectory(Path.GetDirectoryName(exe)!);
        var store = (IPropertyStore)link;
        var key = new PropertyKey(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5);
        var value = new PropVariant(Aumid);
        store.SetValue(ref key, ref value);
        store.Commit();
        ((IPersistFile)link).Save(path, true);
        value.Dispose();
        AppPaths.Log("toast shortcut created");
    }

    [DllImport("shell32.dll", PreserveSig = false)]
    private static extern void SetCurrentProcessExplicitAppUserModelID([MarshalAs(UnmanagedType.LPWStr)] string appId);

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class CShellLink;

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszFile, int cch, IntPtr pfd, uint fFlags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszName, int cch);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszDir, int cch);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszArgs, int cch);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
        void GetHotkey(out short pwHotkey);
        void SetHotkey(short wHotkey);
        void GetShowCmd(out int piShowCmd);
        void SetShowCmd(int iShowCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszIconPath, int cch, out int piIcon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
        void Resolve(IntPtr hwnd, uint fFlags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
    }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
    private interface IPropertyStore
    {
        void GetCount(out uint cProps);
        void GetAt(uint iProp, out PropertyKey pkey);
        void GetValue(ref PropertyKey key, out PropVariant pv);
        void SetValue(ref PropertyKey key, ref PropVariant pv);
        void Commit();
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    private struct PropertyKey(Guid formatId, uint propertyId)
    {
        public Guid FormatId = formatId;
        public uint PropertyId = propertyId;
    }

    /// <summary>文字列だけを入れる PROPVARIANT（VT_LPWSTR）。</summary>
    [StructLayout(LayoutKind.Explicit)]
    private struct PropVariant : IDisposable
    {
        [FieldOffset(0)] private ushort vt;
        [FieldOffset(8)] private IntPtr pointer;

        public PropVariant(string value)
        {
            vt = 31; // VT_LPWSTR
            pointer = Marshal.StringToCoTaskMemUni(value);
        }

        public void Dispose()
        {
            if (pointer != IntPtr.Zero) Marshal.FreeCoTaskMem(pointer);
            pointer = IntPtr.Zero;
        }
    }
}
