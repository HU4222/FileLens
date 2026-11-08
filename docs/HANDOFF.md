# FileLens 浜ゆ帴鏂囦欢锛堟柊浼氳瘽鍏堣杩欎釜锛?

> 璇昏繖涓€浠?鈮?3 鍗?token锛涚炕鑱婂ぉ璁板綍 鈮?38 涓囥€?
> 鏈€鍚庢洿鏂帮細2026-11-08 13:40

---

## 涓€銆佽繖鏄粈涔?

Android 鏂囦欢绠＄悊 App锛屽寘鍚?`com.hu4222.filelens`銆?
浜斾釜涓?Tab锛?*绌洪棿 / 鎼滅储 / 娓呯悊 / 浼犺緭 / 璁剧疆**銆?

## 浜屻€佸伐绋嬩笌鏋勫缓

```
涓荤嚎浠ｇ爜   E:\deepseek宸ヤ綔鍖篭filelens            锛坢ain 鍒嗘敮锛屽綋鍓嶅湪 demo/blur锛?
鏋勫缓宸ヤ綔鏍?E:\deepseek宸ヤ綔鍖篭filelens-main       锛坢ain 妫€鍑猴紝鐢ㄥ畠鏋勫缓涓荤嚎锛?
鏋勫缓鍛戒护   cd filelens-main && cmd /c build_android.bat
git        C:\Users\ZhuanZ锛堟棤瀵嗙爜锛塡AppData\Local\Programs\WorkBuddy\resources\vendor\PortableGit\mingw64\bin\git.exe锛堜笉鍦?PATH锛?
JDK        C:\Program Files\Java\jdk-17
绛惧悕       ANDROID_USER_HOME=.android-home锛堣瘉涔︽寚绾?FA1B29鈥︼級鈽呬笉璁告崲
浜х墿       filelens-main\FileLens-<鐗堟湰>.apk
鐪熸満       PKR110锛坅db锛氭闈鎵嬫満\platform-tools\adb.exe锛屽簭鍒楀彿 3B1F5FE9B8L7QJ0U锛?
```

**绛惧悕蹇呴』鍥哄畾**锛氭崲 keystore 鈫?鎵嬫満瑕嗙洊瀹夎琚嫆 鈫?瑕佸嵏杞介噸瑁呭苟涓㈡潈闄愩€?

## 涓夈€佸綋鍓嶇増鏈笌鐘舵€?

```
褰撳墠鐗堟湰   1.5.0 / versionCode 26锛堜粎 Debug 璇婃柇锛欶LCTL 鎺у埗鐐瑰鍑?+ flTap銆丗LCSTART 鍐峰惎鍔ㄥ洓娈碉紱
                                   鐢ㄦ埛鍙琛屼负鏈彉锛涚湡鏈烘湭楠岋級
1.5.0 鍏抽敭鏁帮細FLCTL 鍙鍑?10 鏉★紙tree 5 + tabbar 5锛夛紱flTap tab:* 鐢熸晥锛沠lTap crumb:0
            鏈鏍戝浘鍥炴牴锛堝緟鏌ワ級锛涘喎鍚姩 snapshot_load鈮?.1s銆乼1_t2鈮?.9s銆乼2_t3鈮?.8s銆?
            total鈮?.8s锛? 娆★紝nodes=9208锛夈€?
宸查獙璇佺増鏈?1.3.7 / versionCode 19锛堢湡鏈洪獙杩囷細榻胯疆 鉁?Shizuku 闅愯棌 鉁?鏍戝浘涓嬮捇 鉁?
                                   鍏綅閰嶅鐮?鉁?灞€鍩熺綉鍦板潃 鉁?鎵爜鎷夎捣鐩告満 鉁?鏈満娴忚鏃犱笅杞芥寜閽?鉁咃級
鏈€杩戞彁浜?  瑙?git log锛?.5.0 鏀跺熬鎻愪氦锛?
           facea13 feat(files): rename, move and new folder with local snapshot patch
```

### 1.4.1 钀界偣锛堟柊浼氳瘽鎸夎繖閲屾煡锛?

**鍘熺敓鏂囦欢鎿嶄綔**

| 鍏ュ彛 | 鏀寔 |
|---|---|
| 鎼滅储缁撴灉闀挎寜 | 閲嶅懡鍚?/ 绉诲姩鍒扳€?/ 鍒犻櫎 |
| 鏍戝浘鍒楄〃闀挎寜 | 閲嶅懡鍚?/ 绉诲姩鍒扳€?/ 鏂板缓鏂囦欢澶癸紙鍒涘缓鍦ㄥ綋鍓嶇洰褰曪紝鏍瑰眰涓嶇粰锛? 澶氶€?|
| 棰勮椤点€屾敼鍚嶃€?| 鍙敼鍚嶏紝鏀瑰畬鏇存柊 `paths[index]` 涓庢爣棰?|

- 鏍￠獙/鎵ц锛歚core/FileOps.kt`鈥斺€旂┖鍚嶃€乣.`/`..`銆乣/ \ : * ? " < > |`銆乼rim銆佸悓鍚嶇洿鎺ュ叧銆佸悓鐩綍宸插瓨鍦ㄦ嫆缁濓紱绉诲姩鏌ヨ嚜韬瓙瀛?鐩爣鍚屽悕/鍚岀洰褰?璺ㄥ嵎
- 鑿滃崟涓庤緭鍏ユ锛歚ui/Dialogs.kt` 鐨?`showActions()` / `showInput()`
- 鐩綍閫夋嫨鍣細`ui/DirPicker.kt`锛堝彧鍒楃洰褰曪紝鏈?NodeStore 璧版爲銆佹病鏈夐€€鍥?File锛?
- 鎾ら攢锛氬鐢ㄥ簳閮?5 绉掓潯锛坄showActionUndo()`锛夛紝閲嶅懡鍚?绉诲姩鍙弽鍚?
- P5 灞€閮ㄥ埛鏂帮細`NodeStore.renameNode()/moveNode()/addDir()/findChild()/indexOfPath()`锛涜矾寰勬寜 parent 閾鹃噸绠楋紝**涓嶉噸鎵?*锛岄〉闈㈤噸璇诲悓涓€ store

**鎺ユ敹鐩綍**

- `core/RecvDir.kt`锛氶粯璁?`Download/FileLens`锛汸refs 瀛樼粷瀵硅矾寰勶紱姣忔浣跨敤鏍￠獙瀛樺湪+鍙啓锛屽潖鎺夊洖榛樿
- `TransferService.recvRoot()` 璧?`RecvDir.resolve()`
- 缃戦〉銆屾垜鐨勩€嶆帴鏀剁洰褰曡锛氭敞鍏ョ偣鍑?鈫?`pickRecvDir()` 妗ワ紱閫夊畬鐩存帴鏀?DOM 涓ゅ璺緞锛屼笉渚濊禆鍒囬〉
- 鍐欐帰閽?`.fl_probe` 鍙湪 `RecvDir.probe()`锛涙柊澧炰唬鐮侀噷鍞竴 `File.delete()`

1.4.1 鑷祴锛堟ā鎷熷櫒锛夛細閲嶅懡鍚嶆垚鍔熴€佸悓鍚嶆嫆缁濄€佸惈 `/` 鎷掔粷骞舵寚鍑哄瓧绗︼紱绉诲姩鎴愬姛锛堟簮娌°€佺洰鏍囨湁銆佹悳绱㈣鐩綍璺熺潃鍙橈級锛?
鏂囦欢澶圭Щ杩涜嚜宸卞瓙鐩綍琚嫆锛涙柊寤烘枃浠跺す鍑虹幇鍦ㄥ綋鍓嶅垪琛ㄤ笖鑳借繘鍏ワ紱鎺ユ敹鐩綍鏀?DCIM 鍚庣綉椤电珛鍗虫樉绀烘柊璺緞銆?
`curl` 涓婁紶鏂囦欢钀藉湪 DCIM锛涘垹鎺?DCIM 閲嶅惎 鈫?鍥為粯璁?+ toast銆?
**鏈祴锛氶瑙堥〉鏀瑰悕銆佹挙閿€鎸夐挳鐐瑰嚮銆佽法瀛樺偍鍗＄Щ鍔ㄣ€佽彍鍗曞閫夈€佹仮澶嶉粯璁ゆ寜閽€傜湡鏈烘湭楠屻€?*

### 1.4.0 涓ゅ钀界偣锛堟柊浼氳瘽鎸夎繖閲屾煡锛?

**A 鎼滅储锛坄ui/SearchPage.kt`锛?*

| # | 鏀瑰姩 | 浣嶇疆 |
|---|---|---|
| A1 | 涓夋ā寮忎簰鏂ワ細`*.png`/`.png` 鍚庣紑銆佸惈 `/` 璧板畬鏁磋矾寰勩€佸惁鍒欏悕绉?| `queryMode()` / `suffixOf()`锛宍perform()` 鍐呭尮閰?|
| A2 | 澶у皬鍥涙。 `<1MB` / `1鈥?0MB` / `>10MB` / `>100MB`锛坄sizeMode` 1鈥?锛?| `buildChips()` |
| A3 | 绗簩琛屾敼銆屽ぇ灏?路 鐩稿鐩綍銆嶏紝`TruncateAt.START`锛涙牴鐩綍鏄剧ず銆屽唴閮ㄥ瓨鍌ㄣ€?| `relativeDir()`銆乣HitAdapter.getView()` |
| A4 | 鍚嶇О/澶у皬/鏃堕棿涓夐€変竴锛岄粯璁ゅぇ灏忛檷搴忥紱鎵弿鏃跺彧鐣欐渶浼?200 鏉★紱鍒囨帓搴忎繚鎸佸垪琛ㄤ綅缃?| `buildSortRow()`銆乣sortHits()`銆乣perform()` |
| A5 | 鍏抽敭璇?0 缁撴灉甯﹀師鏂?+ 璺緞鎻愮ず锛涚函绛涢€?0 缁撴灉缁欍€屾竻绌虹瓫閫夈€?| `renderStatus()`銆乣clearFilters()` |

**B 宕╂簝鏃ュ織**

| 鏂囦欢 | 浣滅敤 |
|---|---|
| `App.kt` + `AndroidManifest.xml` | `Application` 閲屽畨瑁?handler锛沗finally` 杞皟鍘?handler |
| `core/CrashLog.kt` | `files/crash/crash-yyyyMMdd-HHmmss.txt`锛涘悓姝ュ啓 + `fd.sync()`锛涙渶澶?10 浠斤紝`File.delete` 閫愪釜鍒?|
| `ui/CrashLogPage.kt` | 鍒楄〃 / 鍏ㄦ枃 / 鍒嗕韩 / 鍒犻櫎 / 娓呯┖锛涜繑鍥為敭鍏堥€€鍏ㄦ枃鍐嶅叧椤碉紙`handleBack()`锛?|
| `ui/SettingsPage.kt` | 銆岃瘖鏂€嶅尯锛宍宕╂簝鏃ュ織 路 N 浠絗锛宍onCrashLog` |
| `MainActivity.kt` | `showCrashLogPage()`锛氬叏灞?Dialog + 杩斿洖閿鐞嗭紝鍏抽棴鍚庡埛鏂颁唤鏁?|

鑷祴锛堟ā鎷熷櫒 `spacelens_api34`锛宍tools/emu.py`锛夛細build 閫氳繃锛?.4.0/23 鍐峰惎鍔ㄤ笉宕╋紱
`.png` 60 椤瑰叏 png銆乣DCIM/` 璧拌矾寰勬彁绀恒€乣zzzqqq` 鎻愮ず甯﹀師鏂囥€乣SpaceLensTest/` 714 椤癸紱
`<1MB` 8941 椤瑰叏閮?<1MB銆乣1鈥?0MB` 25 椤瑰叏閮?1鈥?0MB锛涘悕绉?澶у皬/鏃堕棿涓夌椤哄簭閮藉锛?
`>100MB` 0 缁撴灉鏄剧ず銆屽綋鍓嶇瓫閫夋潯浠朵笅娌℃湁鏂囦欢 + 娓呯┖绛涢€夈€嶏紱12 娆′汉涓哄穿婧冨彧鐣?10 浠斤紝鍐呭鍚増鏈?鏈哄瀷/鍫嗘爤锛?
鍒嗕韩闈㈡澘銆佸垹闄ゅ崟鏉★紙10鈫?锛夈€佹竻绌哄叏閮紙9鈫?锛夈€佽繑鍥為敭锛堣鎯呪啋鍒楄〃鈫掕缃級鍧囬€氥€傛埅鍥惧湪 `.tmp/emu/`銆?*鐪熸満鏈獙銆?*

> 椤哄甫淇簡涓€涓師鏈夐棶棰橈細澶栧眰 `SwipePager` 浼氭妸鎵€鏈夋í鍚戞嫋鍔ㄥ綋鍒囬〉锛岀瓫閫?chip 閭ｆ帓鍘熸湰婊氫笉鍔紱
> 鐜板湪鍦?`SearchPage` 閲屾墜鎸囨寜涓嬫椂 `requestDisallowInterceptTouchEvent`锛屼笉鏀?`SwipePager`銆?

### 1.3.10 鏂板锛堟柊浼氳瘽鎸夎繖閲屾煡锛?

| 鏂囦欢 | 鐢ㄩ€?|
|---|---|
| `tools/emu.py` | 妯℃嫙鍣ㄩ獙璇?CLI锛坰tatus/start/stop/launch/install/shot/tap/swipe/key/perm/logcat/wait锛夛紱璺緞涓庣鍙ｉ泦涓湪椤堕儴 `CONFIG` |
| `docs/EMU-VERIFY.md` | 浣跨敤璇存槑銆佷笁鏉″父鐢ㄧ粍鍚堛€?.3.9 楠岃瘉鑼冩湰銆佸父瑙佸け璐?|

妯℃嫙鍣細AVD `spacelens_api34`锛屽簭鍒楀彿 `emulator-5556`锛宎db 绔彛 5038銆?
鑷祴宸茬湡璺戯細status锛堝仠/璺戯級銆乻tart銆乮nstall锛?.3.10 / 22锛夈€乻hot锛?080x2340锛夈€?
`perm allfiles deny` + launch 瑙佹彁绀恒€乣perm reset` + launch 鎻愮ず娑堝け銆乻top锛坋mu kill 鏃犳晥鏃惰蛋 reboot -p锛?7s 瀹屾垚锛夈€?

### 1.3.9 涓ゅ钀界偣锛堟柊浼氳瘽鎸夎繖閲屾煡锛?

| # | 鏀瑰姩 | 浣嶇疆 |
|---|---|---|
| P1-1 鎻愮ず鐘舵€?| 鏂板 `syncScanGate()`锛氭湁鏉冮檺 hide銆佹棤鏉冮檺 show锛沗onResume()` 鏈熬 + `selectTab()`/`onPageSettled()` 鐨勭┖闂撮〉鍒嗘敮鍚勮皟涓€娆?| `MainActivity.kt:689-695`銆乣onResume()`銆乣selectTab()`銆乣onPageSettled()` |
| P1-2 鎻愮ず璁╀綅 | 鎻愮ず鏄剧ず鏃剁粰 `scroll` 鍔犲簳閮?`padding = 96dp + 鎻愮ず楂?+ 12dp`锛涢殣钘忔椂褰?0锛沗addOnLayoutChangeListener` 鏍℃棣栨閲忓埌 0 鐨勬儏鍐?| `ui/OverviewPage.kt` `showScanPermissionGate()` / `hideScanPermissionGate()` / `applyGatePad()` |

1.3.9 鑷祴锛堜粎妯℃嫙鍣級锛歜uild 閫氳繃锛涘喎鍚姩 pid 瀛樻椿銆乧rash 缂撳啿鏃犳湰搴旂敤 FATAL锛涙棤鏉冮檺杩涚┖闂撮〉鎻愮ず鍑虹幇锛?
`appops allow` 鍚庡洖鍓嶅彴鎻愮ず娑堝け涓?padding 褰掗浂銆?*鐪熸満鏈獙銆?*

### 1.3.8 涓夊钀界偣锛堟柊浼氳瘽鎸夎繖閲屾煡锛?

| # | 鏀瑰姩 | 浣嶇疆 |
|---|---|---|
| P1-2 鎸夐挳搴曡壊 | `refresh` 鑳屾櫙鏀?`Color.argb(15,255,255,255)`锛屼笌 `qrButton` 鍚屾 | `ui/TransferPage.kt` `refresh` 鍒濆鍖?|
| P1-3 闈㈠寘灞戞牴鍚?| `#crumbs` 棣栨 `/` 娉ㄥ叆鏄剧ず銆屽唴閮ㄥ瓨鍌ㄣ€嶏紝`data-crumb=0` 涓嶅彉 | `ui/TransferPage.kt` `injectViewBridge()` 鏈熬 IIFE |
| P2-1 灞曞紑閬僵 | `scrim` 35% 榛戯紝灞曞紑 150ms 娣″叆銆佹敹璧锋贰鍑哄悗 GONE | `ui/TransferPage.kt` `buildBalls()` / `expandBalls()` / `collapseBalls()` |

P1-1锛堝簳閮ㄨ鑳跺泭閬尅锛夌湡鏈哄疄娴嬩笉鎴愮珛锛歚.views` 宸叉湁 134px 鍐呰竟璺?+ WebView 92dp锛屾粴鍒?
`scrollTop + innerHeight >= scrollHeight - 2` 鏃舵渶鍚庝竴琛屽湪鑳跺泭涓婃柟锛屾湭鏀广€?

1.3.8 鑷祴锛歜uild 閫氳繃锛涘喎鍚姩鏃?FATAL锛涗紶杈撻〉鑳芥墦寮€锛涢〉闈㈠唴瀹炴祴闈㈠寘灞戦娈?銆屽唴閮ㄥ瓨鍌ㄣ€嶃€?
鎮诞鐞冨睍寮€鏃跺唴瀹逛寒搴︿笅闄?35%锛堟埅鍥惧湪 `.tmp/v138-*.png`锛夈€傚姛鑳介獙鏀舵湭鍋氥€?

### 1.3.7 浜旈」钀界偣锛堝巻鍙诧紝鏂颁細璇濇寜杩欓噷鏌ワ級

| # | 鏀瑰湪鍝釜鏂囦欢 / 鍑芥暟 |
|---|---|
| P1-1 璁剧疆榻胯疆 | `ui/Draw.kt` 鐨?`Icon.SETTINGS` 鍒嗘敮锛氬鍦?+ 8 鐭娇 + 涓績绌哄績鍦嗭紝鎻忚竟 |
| P1-2 鏍戝浘/娴嬮€熸墿寮?| `ui/OverviewPage.kt` 鐨?`Content.quickCardRectOnScreen()`銆乣lastQuickCardRectOnScreen()`锛沗MainActivity.kt` 鐨?`showTreeOverlay()` / `hideTreeOverlay()` / `showBenchOverlay()` / `hideBenchOverlay()` |
| P1-3 娓呯悊涓夋 | `ui/CleanPage.kt` 鐨?`selectSegment()`锛?8% 瀹芥í鍚戜綅绉?+ alpha 0.4鈫?锛?00ms |
| P1-4 搴旂敤鎺掑簭 | `ui/CleanPage.kt` 鐨?`buildAppSection()` 鍐呬袱涓帓搴?chip 鐐瑰嚮鐩戝惉锛氱旱鍚?8dp + alpha 0.5鈫?锛?60ms |
| P1-5 Shizuku | `ui/CleanPage.kt` 鐨?`init` 鍒濆 GONE銆乣onShown()` 鍒锋柊銆乣refreshShizuku()` 浠?AUTHORIZED 鏃?VISIBLE |

1.3.7 鑷祴锛歜uild 閫氳繃锛沗am start` 鍐峰惎鍔ㄦ棤 FATAL锛涘簳鏍忚缃?Tab 鑳芥墦寮€璁剧疆椤点€傚姛鑳介獙鏀舵湭鍋氥€?

### 1.3.6 宸查獙杩囩殑浜嬪疄锛堝埆閲嶅楠岋級
- 娓愬彉娣″嚭锛氱敾鍦?`SwipePager.dispatchDraw()`锛屽湪瀛愰〉闈箣鍚庣粯鍒?鈫?z 搴忔纭?
- 鍥炴敹绔欙細璁剧疆椤甸偅涓€琛岀偣杩涘幓鑳界湅鍒扮┖鎬併€屽洖鏀剁珯鏄┖鐨?/ 淇濈暀 30 澶┿€?
- 鐩告満鏉冮檺锛氱偣浜嗚烦銆屽簲鐢ㄨ鎯呴〉銆嶏紙鍙墜鍔ㄥ紑锛夛紝涓嶆槸姝荤偣鍑?

### 宸茬煡娴嬮噺闄烽槺锛堟垜韪╄繃涓夋锛?
**涓嶈闈犳埅鍥句及绠楀潗鏍囧幓鐐规帶浠躲€?* 蹇呴』鍏?`adb shell uiautomator dump` 鎷跨湡瀹?`bounds` 鍐?tap銆?
鎴戝洜涓烘寜缂╂斁姣旀崲绠楀潗鏍囷紝涓夋鎶娿€岀偣鍋忎簡銆嶈鍒ゆ垚銆岀偣鍑诲け鏁堛€嶏紝鐧借寮€鍙戞敼浜嗕袱杞€?

### 宸茬煡涓嶄竴鑷达紙鍒綋 bug 淇級

- 瀛樺偍鏉冮檺鍒ゆ柇鏈変笁濂楀啓娉曪細
  - `SettingsPage.kt:190`锛歋DK >= 30 鈫?`isExternalStorageManager()`锛屽惁鍒欐煡 `READ_EXTERNAL_STORAGE`
  - `MainActivity.kt:680`锛歋DK >= 30 鈫?`isExternalStorageManager()`锛屽惁鍒?`false`
  - `TransferService.kt:87`锛歚SDK_INT < 30 || isExternalStorageManager()`
  API 26鈥?9 涓?`TransferService` 浼氭棤鏉′欢璁や负鏈夋潈闄愩€備富璺緞琚?`hasReadAccess()` 鍏滀綇锛宮inSdk 26 娌″嚭杩囬棶棰橈紱
  **涓嶈鍗曠嫭鏀硅繖涓€澶?*锛岃缁熶竴灏变笁澶勪竴璧锋敼锛屽惁鍒欎紶杈撴湇鍔＄殑瀛樺偍鏍逛細璺熺潃鍙樸€?

## 鍥涖€佷笅涓€姝?

1.4.1 宸插畬鎴愶紱**鏆傚仠鏂板姛鑳斤紝杞叏閲忓洖褰掓祴璇?*锛岀瓑鐢ㄦ埛鐨勫洖褰掓竻鍗曢€愭潯鎵ц銆?
鏂颁細璇濆厛鎸夈€屼笁銆?.4.1 钀界偣銆嶆牳瀵癸紝鐢?`tools\emu.py` 澶嶉獙锛屽啀鎸夊洖褰掓竻鍗曞～缁撴灉銆?

### 宸插畾椤哄簭锛堟殏鍋滐紝绛夊洖褰掑悗鍐嶈锛?
- 1.4.2 SAF 鍩虹璁炬柦 + 鑷畾涔変紶杈撴帴鏀剁洰褰曪紙宸插苟鍏?1.4.1 鐨勬帴鏀剁洰褰曢儴鍒嗭級
- 1.4.3 SAF 鐩綍鎵弿 / 娴忚 / 鍒犻櫎鎺ュ叆锛堜緷璧?1.4.2锛岄『搴忎笉鑳芥崲锛?

### 鏇村悗闈㈢殑锛堟病鎺掓湡锛岀瓑鐢ㄦ埛瀹氾級
- 鐓х墖娓呯悊锛堟柟妗堝湪 `docs/PHOTO-CLEAN-PLAN.md`锛孭1 涓€鍛級
- 姝ｅ紡鍙戝竷绛惧悕 + App 澶囨锛堣鐢ㄦ埛韬唤锛屾垜鍋氫笉鍒帮級
- 妗ョ殑浜嬩欢娴佸 `steer` 涓嶆姤锛堝凡鐭?bug锛岄潬璇讳細璇濇棩蹇楃粫杩囷級

## 浜斻€佺孩绾匡紙杩濆弽鍗冲洖閫€锛?

1. **涓嶆敼 `app/src/main/assets/www/`** 鈥斺€?缃戦〉璧勬簮涓€涓瓧鑺傞兘涓嶅姩锛岃涓烘敼鍔ㄨ蛋杩愯鏃?JS 娉ㄥ叆
2. **`deleteRecursively` 蹇呴』淇濇寔 0 澶?* 鈥斺€?鍒犻櫎涓€寰嬭蛋 `Trash`
3. **涓嶅垹闄ゃ€佷笉鏇挎崲鍘熸湁缁勪欢**锛岄櫎闈炲畠鍔熻兘瀹屽叏涓嶅彲鐢?
4. **鏈€灏忔敼鍔?*锛氬彧鍋氫换鍔′功鍐欑殑锛涙瘡涓敼鍔ㄨ鑳藉洖绛斻€屽搴斿摢涓€鏉°€?
5. **鏂板缓鏂囦欢/鏂板缁勪欢/鏀?鈮? 涓枃浠?鈫?鍏堟姤鎵?*

## 鍏€佸喅绛栬褰曪紙鍒噸鎻愬凡鍚﹀喅鐨勬柟妗堬級

瑙?`docs\DECISIONS.md`銆傚嚑鏉℃渶瀹规槗璧板洖澶磋矾鐨勶細

- **鐜荤拑**锛欰ndroid 娌℃湁绯荤粺绾ц儗鏅ā绯婏紝涓荤嚎鐢ㄤ吉鐜荤拑锛堝崐閫忔槑+楂樺厜+鍣偣锛夈€傚埆鍐嶆彁 `RenderEffect` 鑳界湡妯＄硦銆?
- **鐓х墖娓呯悊**锛氫笉鍋氳缁冩ā鍨嬶紝鍋氥€屾墦鍒?+ 鍘熷瀷妫€绱€嶃€?
- **澶ф枃浠舵帓琛?*锛氬凡鍒狅紝浠峰€间綆銆?
- **浼犺緭**锛氳蛋涓汉鐗堬紙灞€鍩熺綉鍏嶇櫥褰曪級锛屼絾淇濈暀閰嶅鐮佽蛋鎸戞垬-搴旂瓟銆?

## 涓冦€佹枃妗ｇ储寮?

```
docs\DECISIONS.md          鍐崇瓥涓庡惁鍐崇悊鐢?
docs\CHANGELOG.md          鐗堟湰婕旇繘
docs\EMU-VERIFY.md         妯℃嫙鍣ㄩ獙璇佹祦绋嬶紙tools\emu.py锛?
docs\TASK-TEMPLATE.md      浠诲姟涔︽ā鏉?
docs\FIX-1.3.2-DIAGNOSIS.md 1.3.2 涔濅釜闂鐨勬牴鍥?
docs\PHOTO-CLEAN-PLAN.md   鐓х墖娓呯悊鏂规锛堟湭鎺掓湡锛?
AGENTS.md                  缁欏紑鍙?agent 鐨勭‖绾︽潫
```

## 鍏€佸紑鍙戝崗浣滄柟寮?

- 寮€鍙戝湪 **DSH** 閲岃窇锛岀敤妗ラ€氫俊锛歚node tools\bridge_api.mjs POST /api/dsh-bridge/prompt '{"sessionId":"...","text":"..."}'`
- 娲句换鍔＄敤 `node tools\dsh_send.mjs <sessionId> <md鏂囦欢> steer`
- 妗ュ綋鍓嶇増鏈?0.6.3
- **褰撳墠寮€鍙戜細璇?`session-097f03fd-e12f-499b-b3fa-36ef37308e8f`**锛?026-11-08 11:15 璧风敤锛?
- 鏃т細璇?`session-0b986ca7-9f3c-47d7-bbf3-2c20b34ab154` **宸插綊妗?*锛?.48 浜?token锛屼粠渚ц竟鏍忛殣钘忥紝浠呬綔杩芥函锛屼笉瑕佸啀寰€閲屾淳娲伙級
- **妗ョ殑浜嬩欢娴佸 steer 鍙戣捣鐨勮疆娆′笉鎶?* 鈫?鏀逛负璇讳細璇濇棩蹇楋細
  `~/.dsh/sessions/--E-deepseek~5DE5~4F5C~533A--/<sid>/session.v4.jsonl.zstd`
- 鎬濊€冨己搴﹀垏鎹細`tools\effort.bat high|max`锛堟敼閰嶇疆锛岄渶閲嶅惎 DSH 鐢熸晥锛?

## 涔濄€侀獙璇佹柟寮?

鎵嬫満杩?USB 鍚庯細
```bash
ADB="C:\Users\ZhuanZ锛堟棤瀵嗙爜锛塡Desktop\鎵嬫満\platform-tools\adb.exe"
"$ADB" -s 3B1F5FE9B8L7QJ0U install -r <apk>     # 绛惧悕涓€鑷存墠鑳借鐩?
"$ADB" -s 3B1F5FE9B8L7QJ0U exec-out screencap -p > shot.png
"$ADB" -s 3B1F5FE9B8L7QJ0U shell input tap <x> <y>
```

**涓嶈鐢?`adb install` 瑁呮紨绀虹増**锛坄.demo` 鍖呭悕浼氳 InstallerX 鎷︽埅锛夛紱
涓荤嚎鍖呭彲浠ユ甯?`install -r`銆?

