//
//  ViewController.swift
//  Sample
//
//  Created by Dima on 14/03/2023.
//

import UIKit
import Maestro_SDK

@MainActor
class ViewController: UIViewController {
    
    @IBOutlet weak var label: UILabel!

    override func viewDidLoad() {
        super.viewDidLoad()
        
        label.text = "Loading"
        
        let url = URL(string: MaestroSdk.mockServer().url(baseUrl: "https://catfact.ninja/breeds"))!
        
        let task = URLSession.shared.dataTask(with: url) { (data, response, error) in
            DispatchQueue.main.async {
                self.handleResponse(data: String(data: data!, encoding: .utf8)!)
            }
        }
        task.resume()
    }
    
    private func handleResponse(data: String) {
        label.text = data
    }


}

