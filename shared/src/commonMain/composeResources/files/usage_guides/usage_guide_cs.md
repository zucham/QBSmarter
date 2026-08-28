# QBSmarter

> Upozornění: Aplikace QBSmarter se snaží být intuitivní a všechny její funkce by měly být snadno objevitelné.
> Je pravděpodobné, že pro vás čtení následujícího textu nebude příliš užitečné nebo vám nepřinese nic nového.
> 

Aplikace **QBSmarter** je určena speedcuberům, kteří používají chytré Gan Bluetooth kostky a hledají svobodnou
alternativu k oficiální Gan Cube Station aplikaci pro svoje Android telefony.

S QBSmarter můžete nerušeně trénovat skládání na čas ve svižné nativní podobě, která umožňuje nastavovat
styly, jednoduše spravovat data a další užitečné funkce. To vše QBSmarter dělá především bez špehování
uživatelů, zapnuté GPS či potřeby připojení k internetu.

> Upozornění - aplikace je cílená především na pokročilejší speedcubery, kteří rozumí notaci
> tahů pro scrambling bez grafických pomůcek. Vizualizace rozmíchání zatím není podporována.

## Zpětná vazba a návrhy ke zlepšení

Aplikace je v rané fázi vývoje. Pokud jste našli chybu nebo vás napadlo, jak aplikaci vylepšit, můžete 
svůj návrh napsat na [zucham@duck.com](mailto:zucham@duck.com) s předmětem obsahujícím slovo **`QBSmarter`**, nebo
přímo na stránce projektu do sekce **[Issues](https://codeberg.org/zucham/QBSmarter/issues)** na Codebergu.

## Podporovaná zařízení

Nejdříve se ujistěte, že vaše chytrá kostka je na seznamu podporovaných zařízení. Ten je zde:

**Plná podpora**:
- GAN Mini ui FreePlay
- GAN12 ui FreePlay
- GAN12 ui
- GAN356 i Carry S
- GAN356 i Carry
- GAN356 i 3
- Monster Go 3Ai

**Experimentální podpora** (neotestováno, mělo by fungovat):
- GAN356 i Carry 2
- GAN12 ui Maglev
- GAN14 ui FreePlay

V budoucnu bude seznam podporovaných zařízení rozšířen, vše závisí především na dostupnosti kostek
k otestování funkčnosti a zpětné vazbě od uživatelů.

Pokud je kostka v současnosti podporována programem [cstimer.net](https://cstimer.net),
není problém v přidání podpory ani do této aplikace.

### Podpora gyroskopu

Jelikož při vývoji aplikace nebyla k testování dostupná žádná kostka s podporou gyroskopu, je samotná funkcionalita
gyroskopu v aplikaci _experimentální_ a neotestovaná. V případě problémů či poznatků neváhejte s využitím
výše popsaných metod kontaktování a poskytněte zpětnou vazbu.

---

# Začínáme

Nyní si postupně projdeme nejdůležitější části aplikace QBSmarter.
Kromě popisu párování níže budou návody rozděleny podle obrazovky/sekce, ke které přísluší.

## Spárování chytré kostky k aplikaci

Zde je popis základních kroků pro zprovoznění aplikace s chytrou kostkou:

1. Po startu aplikace se objeví hlavní obrazovka s titulkem **Skládání**, kde se nachází časovač, vizualizace kostky a ostatní hlavní elementy. 
2. Pomocí tlačítka **`Připojit kostku`** nebo pomocí navigace v bočním panelu (tlačítko vlevo nahoře) přejdeme na obrazovku **Moje kostky**.
4. Chytrou kostku **zapneme** do režimu párování (většinou stačí několikrát otočit stranami kostky).
   - Kostka by měla začít svítit nebo blikat, pokud uvnitř obsahuje signalizační LED světlo.
3. V aplikaci na obrazovce **Moje Kostky** klikneme na tlačítko **`Spárovat`**.
   - Uživatel musí povolit oprávnění k Bluetooth (starší verze Androidu nerozlišují přístup k Bluetooth a přístup k poloze zařízení) - vyžádáno při startu aplikace.
   - Bluetooth musí být zapnut, aplikace na to upozorní a případně nabídne zkratku do nastavení telefonu pro rychlé zapnutí.
5. Ve vrchní části by se měl objevit seznam okolních Bluetooth zařízení s povolenou viditelností.
   - Veškeré kostky Gan by se měly automaticky objevit na vrcholu seznamu s barevným zvýrazněním.
6. Klepnutím vybereme příslušnou kostku ze seznamu, čímž započne proces párování.
7. Po chvíli by se kostka měla připojit k telefonu, což je indikováno zeleným textem **Připojeno** pod názvem spárované kostky na kartě zařízení..
   - Každá spárovaná kostka zůstane uložena v aplikaci. Pro opětovné připojení již uložené kostky stačí kliknout na tlačítko **`Připojit`** na kartě daného zařízení.
8. Přejdeme zpět na obrazovku **Skládání** buď pomocí tlačítka **`JÍT SKLÁDAT`** (objeví se vlevo nahoře po úspěšném připojení kostky) nebo pomocí bočního panelu jako v kroku č. 2.
9. Nyní by 3D vizualizace kostky na obrazovce měla reagovat na fyzické pohyby stran chytré kostky.
10. Můžeme pokračovat do sekce s popisem časovače, kostka byla úspěšně připojena.

> Upozornění: Aplikace záměrně neskrývá Bluetooth zařízení na základě výrobce, takže na seznamu dostupných zařízení
> v okolí uvidíte i jiná zařízení, než jenom vaši chytrou kostku. Snaha o jejich spárování do aplikace
> povede k nedefinovanému chování. Aplikaci je doporučeno používat pouze s oficiálně podporovanými zařízeními.
> 

## Obrazovka **Skládání**

Hlavní obrazovka aplikace, kde probíhá veškeré skládání. V její horní části se nachází štítek s aktuálně připojenou kostkou - zelená tečka a název kostky znamenají,
že je aplikace připravena přijímat tahy. Pokud kostka není připojená, štítek je šedý, kostka je v pozadí ztmavená a místo barevného štítku se zobrazí
tlačítko **`Připojit kostku`**, které vás navede k spárování, viz popis výše.

Pod štítkem se nachází 3D vizualizace kostky. Tažením prstu po vizualizaci kostkou volně otáčíte; po zvednutí prstu se kostka automaticky zarovná do nejbližší pohledové orientace.
Pokud máte zapnutý gyroskop, vizualizace bude místo toho automaticky kopírovat fyzickou orientaci kostky v prostoru.

Pod kostkou se nachází řada akčních tlačítek:

- **`Resetovat orientaci`** - vrátí pohled na kostku do výchozí orientace (bílá strana nahoře, zelená strana dopředu). Tlačítko je vidět pouze tehdy, kdy je kostka odkloněná od této výchozí orientace.
- **`Gyro`** - zapne nebo vypne řízení vizualizace gyroskopem (pouze pro kostky podporující tuto funkci).
- **`Resetovat stav`** - vrátí logický stav kostky do složené pozice a vygeneruje nové zamíchání. Toto tlačítko je červené, neboť přepíše veškerá data o aktuálním stavu kostky i měření času.

Pod akční řadou se nachází karta se zamícháním (scramblem) a tlačítkem **`Nový`** pro generování nového zamíchání. Pod ní je v dolní části obrazovky časovač a nakonec rychlý přehled statistik (osobní rekord, průměry, počty složení atd.).

### Používání časovače

Hlavním účelem aplikace je automatické měření času během skládání, generování scramblů a ukládání historie skládání. Zde je popis fází časovače:

1. Po připojení kostky je časovač ve fázi **míchání**. Je potřeba následovat instrukce pro rozmíchání.
   - Aplikace automaticky sleduje stav kostky i stav rozmíchání, stačí se držet vizualizace a předpisu již provedených tahů.
   - Pokud v rozmíchání uděláte chybu, aplikace vás pomocí červeně zvýrazněných kroků navede zpět na správnou cestu.
   - Vycházející z pravidel WCA, výchozí pozice kostky je vždy zelenou stranou dopředu a bílou stranou nahoru.
2. Po dokončení rozmíchání se časovač přepne do režimu **prohlížení** (pokud v Nastavení nevypnete možnost `15s čas na prohlédnutí`). Aplikace čeká na první otočení stranou kostky nebo na vypršení časovače.
3. Časovač po prvním otočení strany kostky či vypršení časovače přechází do režimu **měření času**. Měří čas až do úspěšného složení kostky, nebo do přerušení Reset tlačítkem či odpojení zařízení.
4. Po dokončení složení časovač zůstává ve fázi **čekání**. Dokončenému složení je nyní možno přiřadit buď `+2` nebo `DNF`.
5. Pro začátek dalšího složení stačí buď rychle provést pohyb tam a zpět horní stranou (neboli `U U'`) či klepnout na tlačítko Nový v sekci se scramblem.

> Pokud se vám podaří překonat svůj osobní rekord pro aktuální profil, aplikace vám gratuluje vyskakovacím dialogem **Nový rekord!**. Pokud byste si potom rozhodli přiřadit penalizaci `+2` nebo `DNF`, aplikace gratulaci automaticky odebere, pokud složení už nebude rekordem.

## Obrazovka **Moje kostky**

Obrazovka **Moje kostky** slouží ke správě všech chytrých kostek, které jste s aplikací spárovali. Najdete zde dvě hlavní sekce:

- **Spárované kostky** - seznam všech kostek, které jste si k aplikaci dříve přidali. U každé kostky vidíte její název a stav připojení (zelená tečka u připojené kostky, šedá u odpojené).
U aktuálně připojené kostky se vedle názvu zobrazuje navíc i stav baterie. Aktivní kostka je zvýrazněna barevným orámováním.
- **Dostupná zařízení** - seznam okolních zařízení s aktivním Bluetooth (zobrazený jen během vyhledávání). Kostky GAN jsou automaticky setříděny na vrchol seznamu a barevně zvýrazněny.

U každé spárované kostky najdete tato tlačítka:

- **`Připojit`** / **`Odpojit`** - připojí nebo odpojí kostku. V průběhu připojování se na tomto tlačítku zobrazuje načítání. Naráz může být připojena pouze jedna kostka - nové připojení automaticky odpojí předchozí.
- **`Info`** - otevře dialog s podrobnostmi o kostce: MAC adresa, verze hardwaru, verze softwaru, podpora gyroskopu a aktuální stav baterie. Dialog obsahuje také tlačítko **`Upravit`** pro přejmenování kostky.
- **ikona tužky** (vedle názvu kostky) - přejmenuje kostku. Ponecháte-li pole prázdné a uložíte, vrátí se název, který o sobě hlásí samotná kostka.
- **`Zapomenout`** - odebere kostku ze seznamu spárovaných zařízení. Kostku lze samozřejmě v budoucnu znovu spárovat.

V horní části obrazovky najdete různá kontextová tlačítka podle aktuálního stavu:

- Když není připojena žádná kostka, je k dispozici tlačítko **`Spárovat`**, které spustí vyhledávání nových zařízení.
- Když je kostka už připojena, objeví se zde dvě tlačítka: **`JÍT SKLÁDAT`** (vlevo, vede vás zpět na obrazovku skládání) a **`Spárovat novou`** (vpravo, umožní párovat další kostku).
- Během vyhledávání je zde tlačítko **`Zrušit`** pro ukončení skenování.

> Pokud máte vypnuté Bluetooth, aplikace vás na to upozorní a nabídne tlačítko **`Zapnout Bluetooth`**, které vás zavede přímo do systémového nastavení telefonu pro jeho zapnutí.

## Obrazovka **Historie**

Na obrazovce **Historie** najdete úplný přehled všech vašich složení v rámci aktuálního profilu. Záznamy jsou seřazené v podobě seznamu a v horní části obrazovky najdete celkový počet složení a tlačítka pro řazení:

- **Nejnovější** - nejnovější složení nahoře (výchozí řazení).
- **Nejstarší** - nejstarší složení nahoře.
- **Nejlepší** - nejrychlejší složení nahoře (DNF jsou vyfiltrována na konec).
- **Nejhorší** - nejpomalejší složení nahoře (DNF jsou považována za nejhorší a jsou na vrchu).

Každý záznam v seznamu obsahuje:

- Naměřený čas složení (s případným plus znaménkem u `+2` nebo "DNF" u neplatných pokusů).
- Datum a čas složení.
- Průměr z pěti posledních složení (Ao5) v okamžiku tohoto složení, pokud byl k dispozici (tj. existuje aspoň 5 předchozích složení).

**Klepnutím na záznam** otevřete detailní dialog, který obsahuje:

- Datum a čas složení.
- Použité zamíchání (scramble).
- Průměr z posledních pěti složení (Ao5).
- Plynulost (TPS - turns per second, počet otoček za vteřinu).
- Počet otáček (turns) provedených během složení.

V detailním dialogu najdete i tlačítko **Smazat** pro odstranění záznamu (s potvrzením).

**Smazání záznamu** je možné dvěma způsoby:

1. Klepnutím na záznam, otevřením detailního dialogu a klepnutím na **`Smazat`**.
2. Potažením záznamu doprava - objeví se červené pozadí s nápisem "Smazat" a po dotažení dále se otevře potvrzovací dialog.

V obou případech aplikace před smazáním vyžaduje potvrzení, takže se nemusíte obávat náhodného mazání cenných záznamů.

## Obrazovka **Nastavení**

Obrazovka **Nastavení** je rozdělena do několika tematických sekcí:

### Profil

V této sekci spravujete uživatelské profily aplikace. Aplikace podporuje **více profilů** najednou - každý má vlastní historii složení, vlastní seznam spárovaných kostek, vlastní statistiky a dokonce i vlastní nastavení vzhledu a jazyka. To je užitečné, pokud aplikaci sdílíte s někým dalším, nebo si chcete oddělit různé tréninkové režimy.

V seznamu profilů je váš aktivní profil vždy nahoře a je barevně zvýrazněn se štítkem **Aktivní**. Klepnutím na jiný neaktivní profil přepnete na něj. U každého profilu jsou k dispozici tato tlačítka:

- **Ozubené kolo** (na začátku řádku) - otevře dialog s nastavením profilu, kde můžete:
  - upravit zobrazované jméno profilu,
  - vidět celkový počet složení v rámci profilu,
  - exportovat data tohoto profilu do souboru JSON.
- **Koš** (na konci řádku) - smaže profil. Smazání lze také provést potažením profilu doprava. Mazání profilu je nevratné a smažou se s ním všechna související data (historie, kostky, nastavení).

> **Pozor**: Aplikace zaručuje existenci alespoň jednoho profilu. Pokud smažete poslední existující profil, automaticky je vytvořen nový prázdný profil.

Pod seznamem profilů najdete dvě tlačítka:

- **`Vytvořit profil`** - vytvoří nový profil. Pokud nezadáte jméno, profil dostane výchozí název. Po vytvoření je nový profil rovnou aktivován.
- **`Importovat profil`** - načte dříve exportovaný profil ze souboru JSON. Více informací o importu najdete níže.

### Skládání

- **`15s čas na prohlédnutí`** - po dokončení zamíchání aplikace ponechá 15 sekund (dle pravidel WCA) na prohlédnutí kostky před spuštěním časovače. Pokud volbu vypnete, časovač se spustí automaticky při prvním tahu po dokončení zamíchání.
- **`Nevypínat obrazovku při skládání`** - během skládání aplikace zabrání telefonu uspat obrazovku. Po dokončení složení (nebo přechodu na jinou obrazovku) se tento režim opět vypne.

### Zobrazení

- **`Vzhled`** - výběr mezi světlým, tmavým a systémovým motivem.
- **`Barva`** - výběr základní barvy aplikace z 8 barevných palet (modrá, zelená, fialová, oranžová, červená, růžová, žlutá, černobílá - mono).
- **`Volba jazyka`** - výběr jazyka aplikace. Možnosti jsou **Systémově** (aplikace převezme jazyk operačního systému; pokud systémový jazyk není aplikací podporován, je zvolena angličtina) nebo **Ručně**, kde si vyberete konkrétní jazyk z rozbalovacího menu (aktuálně English a Čeština).

> Změna jazyka se aplikuje okamžitě - aplikace automaticky obnoví obrazovku.

### Pokročilé

- **`Používat mezipaměť`** - aplikace si pro rychlejší přechody mezi obrazovkami drží často používaná data v paměti. Volbu doporučujeme nechat zapnutou pro nejlepší zážitek.

### O aplikaci

Zde najdete informaci o aktuální verzi aplikace a unikátní identifikátor aktivního profilu, což může být užitečné při hlášení či ladění chyb. Identifikátor lze označit a zkopírovat.

### Import a export profilových dat

Aplikace umožňuje exportovat svůj kompletní profil (historii složení, spárované kostky a všechna nastavení) do jednoho souboru JSON, a později ho zpět importovat - klidně i na jiném zařízení. Tato funkce slouží jako záloha i jako prostředek pro přenos dat.

**Export** se provádí klepnutím na ozubené kolo u příslušného profilu a na tlačítko **`Exportovat profil`** v dialogu. Aplikace vás vyzve k volbě umístění souboru. Soubor má název `qbsmarter-<jméno_profilu>.json`.

**Import** spustíte tlačítkem **`Importovat profil`** pod seznamem profilů. Aplikace nabídne výběr JSON souboru. Po výběru proběhne import následovně:

- **Pokud importovaný profil ID odpovídá některému stávajícímu profilu**, jeho data se s ním sloučí (merge):
  - Nastavení se přepíší importovanými hodnotami.
  - Spárované kostky a historie složení se připojí, přičemž duplicitní záznamy se přeskočí.
  - Toto je výhoda - opakovaný import stejného souboru profilu nezpůsobí zdvojení dat. Pokud máte stejný profil na více zařízeních a chcete přidat data z jiného zařízení, slučování zachová obě sady.
- **Pokud profil s daným ID lokálně neexistuje**, vytvoří se nový s ID a názvem podle zálohy.
- **Stávající lokální profily, které nejsou v importu**, zůstanou nedotčené.

> **Důležité**: Pokud nejprve **smažete** profil a poté **vytvoříte nový profil se stejným jménem**, nový profil bude mít **jiné interní ID** než ten původní. Pokud následně importujete soubor exportovaný z původního profilu, aplikace **vytvoří třetí, nový profil** (z původního ID v záloze) a nový profil zůstane prázdný. Pokud tedy chcete obnovit starou historii do existujícího profilu, importujte zálohu **dříve**, než stejný profil smažete - jinak ji do něj nepřipojíte.

# Závěr

Pokud jste návod dočetli až sem, patří vám gratulace! Díky, že jste se rozhodli QBSmarter vyzkoušet. Ať vám pomůže s tréninkem a ulehčí práci s chytrými kostkami doma i na cestách.

Pokud máte nápady na vylepšení, narazili jste na chybu či máte jakýkoliv jiný dotaz, neváhejte napsat na [zucham@duck.com](mailto:zucham@duck.com) s předmětem obsahujícím slovo **`QBSmarter`**, nebo otevřít **[Issue](https://codeberg.org/zucham/QBSmarter/issues)** na stránce projektu.

Hodně štěstí při skládání!
