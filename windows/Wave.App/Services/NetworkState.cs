using Windows.Networking.Connectivity;

namespace Wave.App.Services;

/// <summary>
/// 今の通信（ADR-0016 の 8・18）。つながっているか（インターネットに出られるか）と、従量制か（通信料の種類が Unrestricted 以外）を Windows に聞く。
/// </summary>
public static class NetworkState
{
    /// <summary>通信の状態が変わった（Windows の糸から来る）。</summary>
    public static event Action? Changed;

    private static bool watching;

    public static void Watch()
    {
        if (watching) return;
        watching = true;
        NetworkInformation.NetworkStatusChanged += _ => Changed?.Invoke();
    }

    private static ConnectionProfile? Profile()
    {
        try
        {
            return NetworkInformation.GetInternetConnectionProfile();
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// インターネットに出られるか。VPN・トンネル（Tailscale など）の口だけがつながっているときは、外へ出られないとみなす
    /// （ほかに、インターネットに出られる VPN でない口があるときだけ true）。
    /// </summary>
    public static bool IsConnected
    {
        get
        {
            if (DebugOffline is true) return false;
            var profile = Profile();
            if (profile?.GetNetworkConnectivityLevel() != NetworkConnectivityLevel.InternetAccess) return false;
            if (!IsTunnel(profile)) return true;
            try
            {
                return NetworkInformation.GetConnectionProfiles().Any(p => !IsTunnel(p) && p.GetNetworkConnectivityLevel() == NetworkConnectivityLevel.InternetAccess);
            }
            catch (Exception e) when (e is System.Runtime.InteropServices.COMException or UnauthorizedAccessException)
            {
                return false;
            }
        }
    }

    /// <summary>VPN・トンネルの口か（IANA の種類: 131 はトンネル、53 は仮想の口。Tailscale の Wintun は 53）。</summary>
    private static bool IsTunnel(ConnectionProfile profile)
    {
        try
        {
            return profile.NetworkAdapter?.IanaInterfaceType is 131 or 53;
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>従量制の通信か（分からないときも従量制とみなす）。</summary>
    public static bool IsMetered => DebugMetered ?? Profile()?.GetConnectionCost()?.NetworkCostType != NetworkCostType.Unrestricted;

    /// <summary>開発用（DebugCommands）: 通信が無い・従量制のふりをする（null は Windows に聞いた値のまま）。実機の通信を切らずに確かめるため。</summary>
    public static bool? DebugOffline { get; set; }
    public static bool? DebugMetered { get; set; }

    /// <summary>開発用: ふりを変えたことを、通信が変わったときと同じく知らせる。</summary>
    public static void DebugChanged() => Changed?.Invoke();

    /// <summary>記録用の短い説明（通信の種類の名前は書かない）。</summary>
    public static string Describe()
    {
        var profile = Profile();
        if (DebugOffline is true) return "debug-offline";
        return profile is null ? "none" : $"debug-metered={DebugMetered} connected={IsConnected} tunnel={IsTunnel(profile)} level={profile.GetNetworkConnectivityLevel()} cost={profile.GetConnectionCost()?.NetworkCostType}";
    }
}
