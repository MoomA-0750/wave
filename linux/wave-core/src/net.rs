//! 今の通信がインターネットに出られるか（Windows の NetworkState・ADR-0016 と同じ考え）。
//! VPN・トンネル（Tailscale など）の口だけがつながっているときは、外へ出られないとみなす（機内モードでも VPN はつながって見えるため）。
//! Linux では経路の表（/proc/net/route・/proc/net/ipv6_route）の既定の経路が、トンネルでない口にあるかで見る。

/// トンネル・VPN の口の種類（/sys/class/net/<口>/type。ARPHRD_NONE は tun・WireGuard・Tailscale、ほかは IP のトンネル）。
const TUNNEL_TYPES: [u32; 6] = [65534, 768, 769, 776, 778, 823];

pub fn is_tunnel_type(kind: u32) -> bool {
    TUNNEL_TYPES.contains(&kind)
}

/// 既定の経路（IPv4・IPv6）が、トンネルでない口に1つでもあるか。is_tunnel は口の名前を受ける。
pub fn has_internet_route(ipv4_routes: &str, ipv6_routes: &str, is_tunnel: impl Fn(&str) -> bool) -> bool {
    default_route_interfaces(ipv4_routes, ipv6_routes).iter().any(|name| !is_tunnel(name))
}

/// 既定の経路の口の名前。
fn default_route_interfaces(ipv4_routes: &str, ipv6_routes: &str) -> Vec<String> {
    const RTF_UP: u32 = 0x1;
    let mut names = Vec::new();
    // Iface Destination Gateway Flags RefCnt Use Metric Mask ...（1行目は見出し）
    for line in ipv4_routes.lines().skip(1) {
        let fields: Vec<&str> = line.split_whitespace().collect();
        let (Some(iface), Some(destination), Some(flags), Some(mask)) = (fields.first(), fields.get(1), fields.get(3), fields.get(7)) else { continue };
        let up = u32::from_str_radix(flags, 16).is_ok_and(|f| f & RTF_UP != 0);
        if up && *destination == "00000000" && *mask == "00000000" {
            names.push(iface.to_string());
        }
    }
    // 宛先 接頭辞長 送り元 送り元の長さ 次の行き先 距離 RefCnt Use 印 口（見出しは無い）
    for line in ipv6_routes.lines() {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 10 {
            continue;
        }
        let up = u32::from_str_radix(fields[8], 16).is_ok_and(|f| f & RTF_UP != 0);
        if up && fields[0].chars().all(|c| c == '0') && fields[1] == "00" && fields[9] != "lo" {
            names.push(fields[9].to_string());
        }
    }
    names
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEADER: &str = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n";

    fn tunnel(name: &str) -> bool {
        name == "tailscale0" || name == "wg0"
    }

    #[test]
    fn a_default_route_on_a_real_interface_is_online() {
        let routes = format!("{HEADER}ens18\t00000000\t0100A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0\nens18\t0000A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0\n");
        assert!(has_internet_route(&routes, "", tunnel));
    }

    #[test]
    fn only_a_vpn_default_route_is_offline() {
        // 機内モードで、VPN の口だけが既定の経路を持つ
        let routes = format!("{HEADER}wg0\t00000000\t00000000\t0001\t0\t0\t0\t00000000\t0\t0\t0\n");
        assert!(!has_internet_route(&routes, "", tunnel));
        // 既定の経路が無い（Tailscale は自分の経路を別の表に置くので、ここには出ない）
        assert!(!has_internet_route(HEADER, "", tunnel));
    }

    #[test]
    fn ipv6_default_route_counts_and_loopback_does_not() {
        let v6 = "00000000000000000000000000000000 00 00000000000000000000000000000000 00 fe800000000000000000000000000001 00000400 00000001 00000000 00450003 wlp2s0\n\
                  00000000000000000000000000000000 00 00000000000000000000000000000000 00 00000000000000000000000000000000 ffffffff 00000001 00000000 00200200 lo\n";
        assert!(has_internet_route(HEADER, v6, tunnel));
        let only_lo = "00000000000000000000000000000000 00 00000000000000000000000000000000 00 00000000000000000000000000000000 ffffffff 00000001 00000000 00200200 lo\n";
        assert!(!has_internet_route(HEADER, only_lo, tunnel));
    }
}
