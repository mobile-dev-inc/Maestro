
struct KeyModifierFlags: OptionSet {
    let rawValue: UInt64
    static let capsLock =   KeyModifierFlags(rawValue: 1 << 0)
    static let shift =      KeyModifierFlags(rawValue: 1 << 1)
    static let control =    KeyModifierFlags(rawValue: 1 << 2)
    static let option =     KeyModifierFlags(rawValue: 1 << 3)
    static let command =    KeyModifierFlags(rawValue: 1 << 4)
    static let function =   KeyModifierFlags(rawValue: 1 << 5)
}
